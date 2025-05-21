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

package com.hazelcast.client.impl.clientside;

import com.hazelcast.client.test.TestHazelcastFactory;
import com.hazelcast.config.DiagnosticsConfig;
import com.hazelcast.config.DiagnosticsOutputType;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.internal.dynamicconfig.DynamicConfigTest;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.annotation.ParallelJVMTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.After;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

@RunWith(HazelcastParallelClassRunner.class)
@Category({QuickTest.class, ParallelJVMTest.class})
public class ClientDynamicClusterConfigTest extends DynamicConfigTest {

    private TestHazelcastFactory factory;

    @Override
    protected HazelcastInstance[] newInstances() {
        factory = new TestHazelcastFactory();
        return factory.newInstances(getConfig(), INSTANCE_COUNT);
    }

    @Override
    protected HazelcastInstance getDriver() {
        return factory.newHazelcastClient();
    }

    @Override
    public void testDiagnosticsConfig() {
        DiagnosticsConfig config = new DiagnosticsConfig()
                .setEnabled(true)
                .setMaxRolledFileSizeInMB(30)
                .setMaxRolledFileCount(5)
                .setIncludeEpochTime(false)
                .setLogDirectory("/logs")
                .setFileNamePrefix("fileNamePrefix")
                .setAutoOffDurationInMinutes(5)
                .setOutputType(DiagnosticsOutputType.STDOUT);

        UnsupportedOperationException thrown =
                assertThrows(UnsupportedOperationException.class, () -> driver.getConfig().setDiagnosticsConfig(config));

        assertEquals("Client config object does not support setting diagnostics configuration dynamically.",
                thrown.getMessage());
    }

    @After
    public void tearDown() {
        factory.terminateAll();
    }
}
