/*
 * Copyright (c) 2008-2020, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.sql.impl.exec.scan.index;

import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.internal.util.collection.PartitionIdSet;
import com.hazelcast.map.impl.MapContainer;
import com.hazelcast.sql.impl.exec.scan.KeyValueIterator;
import com.hazelcast.sql.impl.exec.scan.MapScanExec;
import com.hazelcast.sql.impl.expression.Expression;
import com.hazelcast.sql.impl.extract.QueryPath;
import com.hazelcast.sql.impl.extract.QueryTargetDescriptor;
import com.hazelcast.sql.impl.type.QueryDataType;

import java.util.List;

/**
 * Index scan executor.
 */
public class MapIndexScanExec extends MapScanExec {

    private final String indexName;
    private final List<IndexFilter> indexFilters;
    private final List<QueryDataType> converterTypes;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public MapIndexScanExec(
        int id,
        MapContainer map,
        PartitionIdSet parts,
        QueryTargetDescriptor keyDescriptor,
        QueryTargetDescriptor valueDescriptor,
        List<QueryPath> fieldPaths,
        List<QueryDataType> fieldTypes,
        List<Integer> projects,
        Expression<Boolean> filter,
        InternalSerializationService serializationService,
        String indexName,
        List<IndexFilter> indexFilters,
        List<QueryDataType> converterTypes
    ) {
        // TODO: How to deal with passed partitions? Should we check that they are still owned during setup?
        super(
            id,
            map,
            parts,
            keyDescriptor,
            valueDescriptor,
            fieldPaths,
            fieldTypes,
            projects,
            filter,
            serializationService
        );

        this.indexName = indexName;
        this.indexFilters = indexFilters;
        this.converterTypes = converterTypes;
    }

    public String getIndexName() {
        return indexName;
    }

    @Override
    protected KeyValueIterator createIterator() {
        return new MapIndexScanExecIterator(map, indexName, indexFilters, converterTypes, ctx);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{mapName=" + mapName + ", fieldPaths=" + fieldPaths + ", projects=" + projects
            + "indexName=" + indexName + ", indexFilters=" + indexFilters + ", remainderFilter=" + filter
            + ", partitionCount=" + partitions.size() + '}';
    }
}
