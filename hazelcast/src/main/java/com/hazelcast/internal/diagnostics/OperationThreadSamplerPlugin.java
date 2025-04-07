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

package com.hazelcast.internal.diagnostics;

import com.hazelcast.internal.util.concurrent.ConcurrentItemCounter;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.spi.impl.operationexecutor.OperationExecutor;
import com.hazelcast.spi.impl.operationexecutor.OperationRunner;
import com.hazelcast.spi.impl.operationservice.NamedOperation;
import com.hazelcast.spi.impl.operationservice.impl.OperationServiceImpl;
import com.hazelcast.spi.properties.HazelcastProperty;

import java.util.concurrent.locks.LockSupport;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * The OperationThreadSamplerPlugin is a {@link DiagnosticsPlugin} that samples the
 * operations threads and checks with operations/tasks are running. We have
 * the slow operation detector; which is very useful for very slow operations.
 * But it isn't useful for high volumes of not too slow operations.
 * <p>
 * With the operation sampler we have a lot better understanding which operations
 * are actually running.
 */
public class OperationThreadSamplerPlugin extends DiagnosticsPlugin {

    /**
     * The sample period in seconds.
     * <p>
     * This is the frequency it will dump content to file. It isn't the frequency
     * that the operations are being sampled.
     *
     * <p>
     * If set to 0, the plugin is disabled.
     */
    public static final HazelcastProperty PERIOD_SECONDS
            = new HazelcastProperty("hazelcast.diagnostics.operationthreadsamples.period.seconds", 0, SECONDS);

    /**
     * The period in milliseconds between taking samples.
     * <p>
     * The lower the period, the higher the overhead, but also the higher the
     * precision.
     */
    public static final HazelcastProperty SAMPLER_PERIOD_MILLIS
            = new HazelcastProperty("hazelcast.diagnostics.operationthreadsamples.sampler.period.millis", 100, MILLISECONDS);

    /**
     * If the name the data-structure the operation operates on should be included.
     * <p>
     * WARNING: This feature should not be used in production because it can lead to
     * quite a lot of litter, and it can lead to uncontrolled memory growth because the
     * samples of old data-structures will not be removed.
     * <p>
     * Also, each time the samples are taken, quite a lot of litter can be generated. So
     * probably you do not want to have a too low SAMPLER_PERIOD_MILLIS because the lower
     * it gets, the more litter is created.
     */
    public static final HazelcastProperty INCLUDE_NAME
            = new HazelcastProperty("hazelcast.diagnostics.operationthreadsamples.includeName", false);
    public static final float HUNDRED = 100f;

    protected final ConcurrentItemCounter<String> partitionSpecificSamples = new ConcurrentItemCounter<>();
    protected final ConcurrentItemCounter<String> genericSamples = new ConcurrentItemCounter<>();
    protected final OperationExecutor executor;
    protected final NodeEngineImpl nodeEngine;
    private boolean includeName;
    private long samplerPeriodMillis;
    private long periodMillis;
    private SampleThread sampleThread;

    public OperationThreadSamplerPlugin(NodeEngineImpl nodeEngine) {
        super(nodeEngine.getConfig().getDiagnosticsConfig(),
                nodeEngine.getLogger(OperationThreadSamplerPlugin.class));
        this.nodeEngine = nodeEngine;
        OperationServiceImpl operationService = nodeEngine.getOperationService();
        this.executor = operationService.getOperationExecutor();
        readProperties();
    }

    private void readProperties() {
        this.periodMillis = nodeEngine.getProperties().getMillis(overrideProperty(PERIOD_SECONDS));
        this.samplerPeriodMillis = nodeEngine.getProperties().getMillis(overrideProperty(SAMPLER_PERIOD_MILLIS));
        this.includeName = nodeEngine.getProperties().getBoolean(overrideProperty(INCLUDE_NAME));
    }

    @Override
    public long getPeriodMillis() {
        return periodMillis;
    }

    @Override
    public void onStart() {
        readProperties();
        super.onStart();
        logger.info("Plugin:active: period-millis:" + periodMillis + " sampler-period-millis:" + samplerPeriodMillis);

        sampleThread = new SampleThread();
        sampleThread.start();
    }

    @Override
    public void onShutdown() {
        sampleThread.interrupt();
        super.onShutdown();
        logger.info("Plugin:deactivated");
    }

    @Override
    public void run(DiagnosticsLogWriter writer) {
        writer.startSection("OperationThreadSamples");
        write(writer, "Partition", partitionSpecificSamples);
        write(writer, "Generic", genericSamples);
        writer.endSection();
    }

    private void write(DiagnosticsLogWriter writer, String text, ConcurrentItemCounter<String> samples) {
        writer.startSection(text);
        long total = samples.total();
        for (String name : samples.keySet()) {
            long s = samples.get(name);
            String entryStr = total == 0L ? String.valueOf(s) : (s + " " + (HUNDRED * s / total) + "%");
            writer.writeKeyValueEntry(name, entryStr);
        }
        writer.endSection();
    }

    boolean getIncludeName() {
        return includeName;
    }

    long getSamplerPeriodMillis() {
        return samplerPeriodMillis;
    }

    protected class SampleThread extends Thread {

        @Override
        public void run() {
            long nextRunMillis = System.currentTimeMillis();

            while (nodeEngine.isRunning() && !Thread.currentThread().isInterrupted()) {
                LockSupport.parkUntil(nextRunMillis);
                nextRunMillis = samplerPeriodMillis;
                sample(executor.getPartitionOperationRunners(), partitionSpecificSamples);
                sample(executor.getGenericOperationRunners(), genericSamples);
            }
        }

        private void sample(OperationRunner[] runners, ConcurrentItemCounter<String> samples) {
            for (OperationRunner runner : runners) {
                Object task = runner.currentTask();
                if (task != null) {
                    samples.inc(toKey(task));
                }
            }
        }

        private String toKey(Object task) {
            String name;
            if (includeName) {
                if (task instanceof NamedOperation operation) {
                    name = task.getClass().getName() + "#" + operation.getName();
                } else {
                    name = task.getClass().getName();
                }
            } else {
                name = task.getClass().getName();
            }
            return name;
        }
    }
}

