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

package com.hazelcast.map.impl.record;

import com.hazelcast.internal.serialization.Data;
import com.hazelcast.internal.tpcengine.util.ReflectionUtil;

import java.lang.invoke.VarHandle;
import java.util.Objects;

import static com.hazelcast.internal.util.JVMUtil.REFERENCE_COST_IN_BYTES;

/**
 * CachedDataRecordWithStats.
 */
class CachedDataRecordWithStats extends DataRecordWithStats {
    private static final VarHandle CACHED_VALUE = ReflectionUtil.findVarHandle("cachedValue", Object.class);

    private transient volatile Object cachedValue;

    CachedDataRecordWithStats() {
    }

    CachedDataRecordWithStats(Data value) {
        super(value);
    }

    @Override
    public void setValue(Data o) {
        super.setValue(o);
        cachedValue = null;
    }

    @Override
    public Object getCachedValueUnsafe() {
        return cachedValue;
    }

    @Override
    public boolean casCachedValue(Object expectedValue, Object newValue) {
        return CACHED_VALUE.compareAndSet(this, expectedValue, newValue);
    }

    @Override
    public long getCost() {
        return super.getCost() + REFERENCE_COST_IN_BYTES;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        if (!super.equals(o)) {
            return false;
        }

        CachedDataRecordWithStats that = (CachedDataRecordWithStats) o;
        return Objects.equals(cachedValue, that.cachedValue);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (cachedValue != null ? cachedValue.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "CachedDataRecordWithStats{"
                + "cachedValue=" + cachedValue
                + ", " + super.toString()
                + "} ";
    }
}
