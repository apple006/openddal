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
// Created on 2015年2月3日
// $Id$

package com.openddal.route;

import java.util.Map;
import java.util.Set;

import com.openddal.dbobject.table.Column;
import com.openddal.dbobject.table.TableMate;
import com.openddal.result.SearchRow;
import com.openddal.route.rule.RoutingResult;
import com.openddal.value.Value;

/**
 * @author jorgie.li
 */
public interface RoutingHandler {
    
    RoutingResult doRoute(TableMate table);

    RoutingResult doRoute(TableMate table, SearchRow row);

    RoutingResult doRoute(TableMate table, SearchRow first, SearchRow last, Map<Column, Set<Value>> inColumns);

}
