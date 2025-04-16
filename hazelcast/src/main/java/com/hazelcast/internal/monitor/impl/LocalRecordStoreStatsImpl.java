/*
 * Copyright (c) 2008-2025, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.internal.monitor.impl;

import com.hazelcast.internal.cluster.Versions;
import com.hazelcast.internal.monitor.LocalRecordStoreStats;
import com.hazelcast.map.impl.MapDataSerializerHook;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;
import com.hazelcast.nio.serialization.impl.Versioned;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

// written by single thread, can be read by multiple threads
public class LocalRecordStoreStatsImpl
        implements LocalRecordStoreStats, IdentifiedDataSerializable, Versioned {

    private static final VarHandle HITS;
    private static final VarHandle EVICTION_COUNT;
    private static final VarHandle EXPIRATION_COUNT;
    private static final VarHandle LAST_ACCESS_TIME;
    private static final VarHandle LAST_UPDATE_TIME;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();

            HITS = l.findVarHandle(LocalRecordStoreStatsImpl.class, "hits", long.class);
            EVICTION_COUNT = l.findVarHandle(LocalRecordStoreStatsImpl.class, "evictionCount", long.class);
            EXPIRATION_COUNT = l.findVarHandle(LocalRecordStoreStatsImpl.class, "expirationCount", long.class);
            LAST_ACCESS_TIME = l.findVarHandle(LocalRecordStoreStatsImpl.class, "lastAccessTime", long.class);
            LAST_UPDATE_TIME = l.findVarHandle(LocalRecordStoreStatsImpl.class, "lastUpdateTime", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private volatile long hits;
    private volatile long lastAccessTime;
    private volatile long lastUpdateTime;
    private volatile long evictionCount;
    private volatile long expirationCount;

    public void copyFrom(LocalRecordStoreStats stats) {
        this.hits = stats.getHits();
        this.lastAccessTime = stats.getLastAccessTime();
        this.lastUpdateTime = stats.getLastUpdateTime();
        this.evictionCount = stats.getEvictionCount();
        this.expirationCount = stats.getExpirationCount();
    }

    @Override
    public long getEvictionCount() {
        return evictionCount;
    }

    @Override
    public long getExpirationCount() {
        return expirationCount;
    }

    @Override
    public long getHits() {
        return hits;
    }

    @Override
    public long getLastAccessTime() {
        return lastAccessTime;
    }

    @Override
    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    @Override
    public void setLastAccessTime(long time) {
        LAST_ACCESS_TIME.setOpaque(this, Math.max(lastAccessTime, time));
    }

    @Override
    public void setLastUpdateTime(long time) {
        LAST_UPDATE_TIME.setOpaque(this, Math.max(lastUpdateTime, time));
    }

    @Override
    public void increaseEvictions() {
        EVICTION_COUNT.setOpaque(this, evictionCount + 1);
    }

    @Override
    public void increaseExpirations() {
        EXPIRATION_COUNT.setOpaque(this, expirationCount + 1);
    }

    @Override
    public void increaseHits() {
        HITS.setOpaque(this, hits + 1);
    }

    public void reset() {
        this.hits = 0;
        this.lastAccessTime = 0;
        this.lastUpdateTime = 0;
        this.evictionCount = 0;
        this.expirationCount = 0;
    }

    @Override
    public void writeData(ObjectDataOutput out) throws IOException {
        out.writeLong(hits);
        out.writeLong(lastAccessTime);
        out.writeLong(lastUpdateTime);

        // RU_COMPAT 5.2
        if (out.getVersion().isGreaterOrEqual(Versions.V5_3)) {
            out.writeLong(evictionCount);
            out.writeLong(expirationCount);
        }
    }

    @Override
    public void readData(ObjectDataInput in) throws IOException {
        hits = in.readLong();
        lastAccessTime = in.readLong();
        lastUpdateTime = in.readLong();

        // RU_COMPAT 5.2
        if (in.getVersion().isGreaterOrEqual(Versions.V5_3)) {
            evictionCount = in.readLong();
            expirationCount = in.readLong();
        }
    }

    @Override
    public int getFactoryId() {
        return MapDataSerializerHook.F_ID;
    }

    @Override
    public int getClassId() {
        return MapDataSerializerHook.LOCAL_RECORD_STORE_STATS;
    }
}
