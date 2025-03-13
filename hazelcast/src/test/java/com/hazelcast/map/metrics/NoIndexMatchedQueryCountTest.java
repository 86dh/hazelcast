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

package com.hazelcast.map.metrics;

import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.internal.monitor.impl.LocalMapStatsImpl;
import com.hazelcast.map.IMap;
import com.hazelcast.query.impl.predicates.EqualPredicate;
import com.hazelcast.test.HazelcastTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.hazelcast.internal.metrics.MetricDescriptorConstants.MAP_METRIC_NO_MATCHING_INDEX_QUERY_COUNT;
import static com.hazelcast.map.metrics.MetricTestUtils.assertAttributeEquals;
import static com.hazelcast.map.metrics.MetricTestUtils.buildMapMetricName;
import static com.hazelcast.test.annotation.ParallelJVMTest.PARALLEL_JVM_TEST;
import static com.hazelcast.test.annotation.QuickTest.QUICK_TEST;
import static org.assertj.core.api.Assertions.assertThat;

@Tag(PARALLEL_JVM_TEST)
@Tag(QUICK_TEST)
class NoIndexMatchedQueryCountTest
        extends HazelcastTestSupport {

    @Test
    void testCounterIncrementing() {
        Config config = MetricTestUtils.setRapidMetricsCollection(smallInstanceConfig()).addMapConfig(
                new MapConfig().setName("testMap").addIndexConfig(User.getIndexConfigOnUserId()));

        HazelcastInstance hz = createHazelcastInstance(config);
        IMap<Integer, User> testMap = hz.getMap("testMap");
        testMap.put(0, new User("123", "345"));

        for (int i = 0; i < 5; i++) {
            testMap.entrySet(new EqualPredicate("userId", "" + i));
        }

        for (int i = 0; i < 12; i++) {
            testMap.entrySet(new EqualPredicate("name", "" + i));
        }

        LocalMapStatsImpl stats = (LocalMapStatsImpl) testMap.getLocalMapStats();
        assertThat(stats.getQueryCount()).isEqualTo(17);
        assertThat(stats.getIndexedQueryCount()).isEqualTo(5);
        assertThat(stats.getIndexesSkippedQueryCount()).isZero();

        assertThat(stats.getNoMatchingIndexQueryCount()).isEqualTo(12);
        assertAttributeEquals(12L, buildMapMetricName(hz, "testMap"), MAP_METRIC_NO_MATCHING_INDEX_QUERY_COUNT);
    }
}
