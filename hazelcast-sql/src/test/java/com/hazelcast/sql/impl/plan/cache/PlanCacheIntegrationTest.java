/*
 * Copyright 2021 Hazelcast Inc.
 *
 * Licensed under the Hazelcast Community License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://hazelcast.com/hazelcast-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.sql.impl.plan.cache;

import com.hazelcast.config.IndexType;
import com.hazelcast.jet.sql.SqlTestSupport;
import com.hazelcast.map.IMap;
import com.hazelcast.sql.impl.optimizer.SqlPlan;
import com.hazelcast.test.HazelcastSerialClassRunner;
import com.hazelcast.test.annotation.ParallelJVMTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

@RunWith(HazelcastSerialClassRunner.class)
@Category({QuickTest.class, ParallelJVMTest.class})
public class PlanCacheIntegrationTest extends PlanCacheTestSupport {
    private String mapName;

    @BeforeClass
    public static void setUp() {
        initialize(2, smallInstanceConfig());
    }

    @Before
    public void before() {
        mapName = SqlTestSupport.randomName();
    }

    @Test
    public void testPlanIsCached() {
        instance().getMap(mapName).put(1, 1);

        PlanCache planCache = planCache(instance());

        instance().getSql().execute("SELECT * FROM " + mapName);
        assertEquals(1, planCache.size());
        SqlPlan plan1 = planCache.get(planCache.getPlans().keys().nextElement());

        instance().getSql().execute("SELECT * FROM " + mapName);
        assertEquals(1, planCache.size());
        SqlPlan plan2 = planCache.get(planCache.getPlans().keys().nextElement());
        assertSame(plan1, plan2);
    }

    @Test
    public void testPlanInvalidatedOnIndexAdd() {
        IMap<Integer, Integer> map = instance().getMap(mapName);
        map.put(1, 1);

        PlanCache planCache = planCache(instance());

        instance().getSql().execute("SELECT * FROM " + mapName + " WHERE this=1");
        assertEquals(1, planCache.size());
        SqlPlan plan1 = planCache.get(planCache.getPlans().keys().nextElement());

        map.addIndex(IndexType.HASH, "this");

        assertTrueEventually(() -> {
            instance().getSql().execute("SELECT * FROM " + mapName + " WHERE this=1");
            assertEquals(1, planCache.size());
            SqlPlan plan2 = planCache.get(planCache.getPlans().keys().nextElement());
            assertNotSame(plan1, plan2);
        });
    }

    @Test
    public void testPlanInvalidatedOnMapDestroy() {
        IMap<Integer, Integer> map = instance().getMap(mapName);
        map.put(1, 1);

        PlanCache planCache = planCache(instance());

        instance().getSql().execute("SELECT * FROM " + mapName);
        assertEquals(1, planCache.size());

        map.destroy();
        assertTrueEventually(() -> assertEquals(0, planCache.size()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    public void testPlanInvalidatedOnMapSchemaChange() {
        IMap map = instance().getMap(mapName);
        map.put(1, 1);

        PlanCache planCache = planCache(instance());

        instance().getSql().execute("SELECT * FROM " + mapName);
        assertEquals(1, planCache.size());
        SqlPlan plan1 = planCache.get(planCache.getPlans().keys().nextElement());

        map.clear();
        map.put("1", "1");

        assertTrueEventually(() -> {
            instance().getSql().execute("SELECT * FROM " + mapName);
            assertEquals(1, planCache.size());
            SqlPlan plan2 = planCache.get(planCache.getPlans().keys().nextElement());
            assertNotSame(plan1, plan2);
        });
    }
}
