/*
 * Copyright 2014-2016 the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the “License”);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an “AS IS” BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.openddal.server.mysql;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openddal.server.NettyServer;
import com.openddal.server.ServerException;
import com.openddal.server.core.ServerSession;
import com.openddal.server.mysql.auth.Privilege;
import com.openddal.server.mysql.proto.ColumnDefinition;
import com.openddal.server.mysql.proto.ComFieldlist;
import com.openddal.server.mysql.proto.ComInitdb;
import com.openddal.server.mysql.proto.ComPing;
import com.openddal.server.mysql.proto.ComProcesskill;
import com.openddal.server.mysql.proto.ComQuery;
import com.openddal.server.mysql.proto.ComQuit;
import com.openddal.server.mysql.proto.ComShutdown;
import com.openddal.server.mysql.proto.ComStmtClose;
import com.openddal.server.mysql.proto.ComStmtExecute;
import com.openddal.server.mysql.proto.ComStmtPrepare;
import com.openddal.server.mysql.proto.ComStmtReset;
import com.openddal.server.mysql.proto.ComStmtSendLongData;
import com.openddal.server.mysql.proto.ERR;
import com.openddal.server.mysql.proto.Flags;
import com.openddal.server.mysql.proto.Handshake;
import com.openddal.server.mysql.proto.HandshakeResponse;
import com.openddal.server.mysql.proto.OK;
import com.openddal.server.mysql.proto.Packet;
import com.openddal.server.mysql.proto.Resultset;
import com.openddal.server.mysql.proto.ResultsetRow;
import com.openddal.server.util.CharsetUtil;
import com.openddal.server.util.ErrorCode;
import com.openddal.server.util.MysqlDefs;
import com.openddal.server.util.ResultSetUtil;
import com.openddal.server.util.StringUtil;
import com.openddal.util.StringUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * @author <a href="mailto:jorgie.mail@gmail.com">jorgie li</a>
 *
 */
public class MySQLServerHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(MySQLServerHandler.class);

    private long sequenceId;
    private ThreadPoolExecutor userExecutor;
    private NettyServer server;
    private ServerSession session;

    public MySQLServerHandler(NettyServer server) {
        this.server = server;
        this.userExecutor = server.getUserExecutor();
        this.session = new ServerSession(server);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ByteBuf out = ctx.alloc().buffer();
        Handshake handshake = new Handshake();
        handshake.sequenceId = 0;
        handshake.protocolVersion = MySQLServer.PROTOCOL_VERSION;
        handshake.serverVersion = MySQLServer.SERVER_VERSION;
        handshake.connectionId = session.getThreadId();
        handshake.challenge1 = StringUtil.getRandomString(8);
        handshake.characterSet = CharsetUtil.getIndex(MySQLServer.DEFAULT_CHARSET);
        handshake.statusFlags = Flags.SERVER_STATUS_AUTOCOMMIT;
        handshake.challenge2 = StringUtil.getRandomString(12);
        handshake.authPluginDataLength = 21;
        handshake.authPluginName = Flags.MYSQL_NATIVE_PASSWORD;
        handshake.capabilityFlags = Flags.CLIENT_BASIC_FLAGS;
        handshake.removeCapabilityFlag(Flags.CLIENT_COMPRESS);
        handshake.removeCapabilityFlag(Flags.CLIENT_SSL);
        handshake.removeCapabilityFlag(Flags.CLIENT_LOCAL_FILES);
        session.setCharsetIndex((int) handshake.characterSet);
        session.setAttachment("seed", handshake.challenge1 + handshake.challenge2);
        out.writeBytes(handshake.toPacket());
        ctx.writeAndFlush(out);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (ServerSession.get(ctx.channel()) != this.session) {
            authenticate(ctx, msg);
        } else {
            ByteBuf buf = (ByteBuf) msg;
            userExecutor.execute(new HandleTask(ctx, buf));
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ServerSession session = ServerSession.get(ctx.channel());
        if (session != null) {
            session.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.warn("exceptionCaught", cause);
        ctx.close();
    }

    private void authenticate(ChannelHandlerContext ctx, Object msg) {
        Privilege privilege = server.getPrivilege();
        HandshakeResponse authReply = null;
        ByteBuf buf = (ByteBuf) msg;
        try {
            byte[] packet = new byte[buf.readableBytes()];
            buf.readBytes(packet);
            authReply = HandshakeResponse.loadFromPacket(packet);
            this.sequenceId = authReply.sequenceId;
            if (!authReply.hasCapabilityFlag(Flags.CLIENT_PROTOCOL_41)) {
                sendError(ctx, ErrorCode.ER_NOT_SUPPORTED_AUTH_MODE, "We do not support Protocols under 4.1");
                return;
            }
            if (!privilege.userExists(authReply.username)) {
                sendError(ctx, ErrorCode.ER_ACCESS_DENIED_ERROR, "Access denied for user '" + authReply.username + "'");
                return;
            }
            if (!StringUtil.isEmpty(authReply.schema)
                    && !privilege.schemaExists(authReply.username, authReply.schema)) {
                String s = "Access denied for user '" + authReply.username + "' to database '" + authReply.schema + "'";
                sendError(ctx, ErrorCode.ER_DBACCESS_DENIED_ERROR, s);
                return;
            }
            String seed = session.getAttachment("seed");
            if (!privilege.checkPassword(authReply.username, authReply.authResponse, seed)) {
                sendError(ctx, ErrorCode.ER_ACCESS_DENIED_ERROR, "Access denied for user '" + authReply.username + "'");
                return;
            }
            session.setUser(authReply.username);
            session.setSchema(authReply.schema);
            session.bind(ctx.channel());
            session.setAttachment("remoteAddress", ctx.channel().remoteAddress().toString());
            session.setAttachment("localAddress", ctx.channel().localAddress().toString());
            success(ctx);
        } catch (Exception e) {
            String errMsg = authReply == null ? e.getMessage()
                    : "Access denied for user '" + authReply.username + "' to database '" + authReply.schema + "'";
            LOGGER.error("Authorize failed. " + errMsg, e);
            sendError(ctx, ErrorCode.ER_DBACCESS_DENIED_ERROR, errMsg);
        } finally {
            buf.release();
        }
    }


    private void despatchCommand(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        this.sequenceId = Packet.getSequenceId(data);
        Packet packet = null;

        byte type = Packet.getType(data);
        switch (type) {
        case Flags.COM_INIT_DB:
            packet = ComInitdb.loadFromPacket(data);
            init(ctx, (ComInitdb) packet);
            break;
        case Flags.COM_QUERY:
            packet = ComQuery.loadFromPacket(data);
            query(ctx, (ComQuery) packet);
            break;
        case Flags.COM_PING:
            packet = ComPing.loadFromPacket(data);
            ping(ctx, (ComPing) packet);
            break;
        case Flags.COM_QUIT:
            packet = ComQuit.loadFromPacket(data);
            close(ctx, (ComQuit) packet);
            break;
        case Flags.COM_PROCESS_KILL:
            packet = ComProcesskill.loadFromPacket(data);
            processKill(ctx, (ComProcesskill) packet);
            break;
        case Flags.COM_STMT_PREPARE:
            packet = ComStmtPrepare.loadFromPacket(data);
            stmtPrepare(ctx, (ComStmtPrepare) packet);
            break;
        case Flags.COM_STMT_SEND_LONG_DATA:
            packet = ComStmtSendLongData.loadFromPacket(data);
            stmtPrepareLongData(ctx, (ComStmtSendLongData) packet);
            break;
        case Flags.COM_STMT_EXECUTE:
            packet = ComStmtExecute.loadFromPacket(data);
            stmtExecute(ctx, (ComStmtExecute) packet);
            break;
        case Flags.COM_STMT_CLOSE:
            packet = ComStmtClose.loadFromPacket(data);
            stmtClose(ctx, (ComStmtClose) packet);
            break;
        case Flags.COM_SHUTDOWN:
            packet = ComShutdown.loadFromPacket(data);
            shutdown(ctx, (ComShutdown) packet);
            break;
        case Flags.COM_STMT_RESET:
            packet = ComStmtReset.loadFromPacket(data);
            break;
        case Flags.COM_FIELD_LIST:
            packet = ComFieldlist.loadFromPacket(data);
            break;
        default:
            throw new ServerException(ErrorCode.ER_UNKNOWN_COM_ERROR, "Unknown command");
        }
    }

    private void shutdown(ChannelHandlerContext ctx, ComShutdown request) {
        ctx.channel().close();
        ctx.channel().parent().close();
        server.stop();
        System.exit(0);
    }

    private void ping(ChannelHandlerContext ctx, ComPing request) {
        success(ctx);
    }

    private void init(ChannelHandlerContext ctx, ComInitdb request) {
        session.setSchema(request.schema);
        success(ctx);
    }

    private void query(ChannelHandlerContext ctx, ComQuery request) throws Exception {
        String sql = request.query;
        if (StringUtils.isNullOrEmpty(sql)) {
            sendError(ctx, ErrorCode.ER_NOT_ALLOWED_COMMAND, "Empty SQL");
            return;
        }
        
    }

    private void close(ChannelHandlerContext ctx, ComQuit request) {
        success(ctx);
    }

    private void stmtPrepare(ChannelHandlerContext ctx, ComStmtPrepare request) {
        sendError(ctx, ErrorCode.ER_UNKNOWN_COM_ERROR, "Prepare command unsupported.");
    }

    private void stmtPrepareLongData(ChannelHandlerContext ctx, ComStmtSendLongData request) {
        sendError(ctx, ErrorCode.ER_UNKNOWN_COM_ERROR, "Prepare command unsupported.");
    }

    private void stmtExecute(ChannelHandlerContext ctx, ComStmtExecute request) throws Exception {
        sendError(ctx, ErrorCode.ER_UNKNOWN_COM_ERROR, "Prepare command unsupported.");
    }

    private void stmtClose(ChannelHandlerContext ctx, ComStmtClose request) {
        sendError(ctx, ErrorCode.ER_UNKNOWN_COM_ERROR, "Prepare command unsupported.");
    }

    private void processKill(ChannelHandlerContext ctx, ComProcesskill request) {
        ServerSession s = server.getSession(request.connectionId);
        if (s != null) {
            s.close();
        }
        success(ctx);
    }

    private long nextSequenceId() {
        return ++sequenceId;
    }

    private void success(ChannelHandlerContext ctx) {
        ByteBuf out = ctx.alloc().buffer();
        OK ok = new OK();
        ok.sequenceId = nextSequenceId();
        ok.setStatusFlag(Flags.SERVER_STATUS_AUTOCOMMIT);
        out.writeBytes(ok.toPacket());
        ctx.writeAndFlush(out);
    }

    private void sendError(ChannelHandlerContext ctx, int errno, String msg) {
        ByteBuf out = ctx.alloc().buffer();
        ERR err = new ERR();
        err.sequenceId = nextSequenceId();
        err.errorCode = errno;
        err.errorMessage = msg;
        out.writeBytes(err.toPacket());
    }

    private void sendUpdateResult(ChannelHandlerContext ctx, int rows) {
        ByteBuf out = ctx.alloc().buffer();
        OK ok = new OK();
        ok.sequenceId = nextSequenceId();
        ok.affectedRows = rows;
        ok.setStatusFlag(Flags.SERVER_STATUS_AUTOCOMMIT);
        out.writeBytes(ok.toPacket());
        ctx.writeAndFlush(out);
    }

    private void sendQueryResult(ChannelHandlerContext ctx, ResultSet rs) throws Exception {
        ByteBuf out = ctx.alloc().buffer();
        ResultSetMetaData metaData = rs.getMetaData();
        int colunmCount = metaData.getColumnCount();

        Resultset resultset = new Resultset();
        resultset.sequenceId = nextSequenceId();
        Resultset.characterSet = session.getCharsetIndex();

        for (int i = 0; i < colunmCount; i++) {
            int j = i + 1;
            ColumnDefinition columnPacket = new ColumnDefinition();
            columnPacket.org_name = StringUtil.emptyIfNull(metaData.getColumnName(j));
            columnPacket.name = StringUtil.emptyIfNull(metaData.getColumnLabel(j));
            columnPacket.org_table = StringUtil.emptyIfNull(metaData.getTableName(j));
            columnPacket.table = StringUtil.emptyIfNull(metaData.getTableName(j));
            columnPacket.schema = StringUtil.emptyIfNull(metaData.getSchemaName(j));
            columnPacket.flags = ResultSetUtil.toFlag(metaData, j);
            columnPacket.columnLength = metaData.getColumnDisplaySize(j);
            columnPacket.decimals = metaData.getScale(j);
            int javaType = MysqlDefs.javaTypeDetect(metaData.getColumnType(j), (int) columnPacket.decimals);
            columnPacket.type = (byte) (MysqlDefs.javaTypeMysql(javaType) & 0xff);
            resultset.addColumn(columnPacket);
        }

        while (rs.next()) {
            ResultsetRow rowPacket = new ResultsetRow();
            for (int i = 0; i < colunmCount; i++) {
                int j = i + 1;
                rowPacket.data.add(StringUtil.emptyIfNull(rs.getString(j)));
            }
            resultset.addRow(rowPacket);
        }
        ArrayList<byte[]> packets = resultset.toPackets();
        for (byte[] bs : packets) {
            out.writeBytes(bs);
        }
        ctx.writeAndFlush(out);
    }

    /**
     * Execute the processor in user threads.
     */
    private class HandleTask implements Runnable {
        private final ChannelHandlerContext ctx;
        private final ByteBuf buf;

        private HandleTask(ChannelHandlerContext ctx, ByteBuf buf) {
            this.ctx = ctx;
            this.buf = buf;
        }

        @Override
        public void run() {
            try {
                despatchCommand(ctx, buf);
            } catch (Throwable e) {
                LOGGER.error("an exception happen when process request", e);
                ServerException ex = ServerException.convert(e);
                sendError(ctx, ex.getErrorCode(), ex.getMessage());
            } finally {
                buf.release();
            }
        }

    }

}
