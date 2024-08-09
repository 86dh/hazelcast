/*
 * Copyright (c) 2008-2024, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.client.impl.protocol.task.map;

import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.impl.protocol.codec.MapValuesCodec;
import com.hazelcast.instance.impl.Node;
import com.hazelcast.internal.nio.Connection;
import com.hazelcast.internal.serialization.Data;
import com.hazelcast.internal.util.IterationType;
import com.hazelcast.map.impl.MapService;
import com.hazelcast.map.impl.MapServiceContext;
import com.hazelcast.map.impl.query.QueryResultRow;
import com.hazelcast.query.Predicate;
import com.hazelcast.query.Predicates;
import com.hazelcast.security.SecurityInterceptorConstants;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.hazelcast.map.impl.LocalMapStatsUtil.incrementOtherOperationsCount;

public class MapValuesMessageTask
        extends DefaultMapQueryMessageTask<String> {

    public MapValuesMessageTask(ClientMessage clientMessage, Node node, Connection connection) {
        super(clientMessage, node, connection);
    }

    @Override
    protected Object reduce(Collection<QueryResultRow> result) {
        List<Data> values = new ArrayList<>(result.size());
        for (QueryResultRow resultEntry : result) {
            values.add(resultEntry.getValue());
        }
        MapService mapService = (MapService) getService(MapService.SERVICE_NAME);
        incrementOtherOperationsCount(mapService, parameters);
        incrementMapMetric(mapService, parameters);
        return values;
    }

    @Override
    protected Predicate getPredicate() {
        return Predicates.alwaysTrue();
    }

    @Override
    protected IterationType getIterationType() {
        return IterationType.VALUE;
    }

    @Override
    protected String decodeClientMessage(ClientMessage clientMessage) {
        return MapValuesCodec.decodeRequest(clientMessage);
    }

    @Override
    protected ClientMessage encodeResponse(Object response) {
        return MapValuesCodec.encodeResponse((List<Data>) response);
    }

    @Override
    public String getDistributedObjectName() {
        return parameters;
    }

    @Override
    public String getMethodName() {
        return SecurityInterceptorConstants.VALUES;
    }

    @Override
    public Object[] getParameters() {
        return null;
    }

    private void incrementMapMetric(MapService service, String mapName) {
        MapServiceContext mapServiceContext = service.getMapServiceContext();
        if (mapServiceContext.getMapContainer(mapName).getMapConfig().isStatisticsEnabled()) {
            mapServiceContext.getLocalMapStatsProvider()
                    .getLocalMapStatsImpl(mapName)
                    .incrementValuesCallCount();
        }
    }

}
