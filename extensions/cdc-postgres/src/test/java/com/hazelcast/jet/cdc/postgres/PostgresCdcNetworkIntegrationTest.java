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

package com.hazelcast.jet.cdc.postgres;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.JetException;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.cdc.AbstractCdcIntegrationTest;
import com.hazelcast.jet.cdc.ChangeRecord;
import com.hazelcast.jet.pipeline.Pipeline;
import com.hazelcast.jet.pipeline.Sinks;
import com.hazelcast.jet.pipeline.StreamSource;
import com.hazelcast.jet.retry.RetryStrategies;
import com.hazelcast.jet.retry.RetryStrategy;
import com.hazelcast.test.HazelcastParametrizedRunner;
import com.hazelcast.test.HazelcastSerialParametersRunnerFactory;
import com.hazelcast.test.annotation.NightlyTest;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import static com.hazelcast.jet.TestedVersions.DEBEZIUM_POSTGRES_IMAGE;
import static com.hazelcast.jet.TestedVersions.TOXIPROXY_IMAGE;
import static com.hazelcast.jet.Util.entry;
import static com.hazelcast.jet.cdc.postgres.AbstractPostgresCdcIntegrationTest.getConnection;
import static com.hazelcast.jet.cdc.postgres.PostgresCdcSources.PostgresSnapshotMode.INITIAL;
import static com.hazelcast.jet.core.JobAssertions.assertThat;
import static com.hazelcast.jet.core.JobStatus.RUNNING;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.runners.Parameterized.UseParametersRunnerFactory;
import static org.testcontainers.containers.PostgreSQLContainer.POSTGRESQL_PORT;

@SuppressWarnings("resource")
@RunWith(HazelcastParametrizedRunner.class)
@UseParametersRunnerFactory(HazelcastSerialParametersRunnerFactory.class)
@Category({NightlyTest.class})
public class PostgresCdcNetworkIntegrationTest extends AbstractCdcIntegrationTest {

    private static final long RECONNECT_INTERVAL_MS = SECONDS.toMillis(1);
    private static final String NETWORK_ALIAS = "postgres";
    private static final String UPSTREAM = "postgres:5432";

    @Parameter
    public RetryStrategy reconnectBehavior;

    @Parameter(value = 1)
    public boolean resetStateOnReconnect;

    @Parameter(value = 2)
    public String testName;

    private PostgreSQLContainer<?> postgres;

    @Parameters(name = "{2}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {RetryStrategies.never(), false, "fail"},
                {RetryStrategies.indefinitely(RECONNECT_INTERVAL_MS), false, "reconnect"},
                {RetryStrategies.indefinitely(RECONNECT_INTERVAL_MS), true, "reconnect w/ state reset"}
        });
    }

    @After
    public void after() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    public void when_noDatabaseToConnectTo() {
        postgres = initPostgres(null, null);
        int port = fixPortBinding(postgres, POSTGRESQL_PORT);
        String containerIpAddress = postgres.getHost();
        stopContainer(postgres);

        Pipeline pipeline = initPipeline(containerIpAddress, port);

        // when job starts
        HazelcastInstance hz = createHazelcastInstances(2)[0];
        Job job = hz.getJet().newJob(pipeline);
        // then
        boolean neverReconnect = reconnectBehavior.getMaxAttempts() == 0;
        if (neverReconnect) {
            // then job fails
            assertThatThrownBy(job::join)
                    .hasCauseInstanceOf(JetException.class)
                    .hasStackTraceContaining("Failed to connect to database");
            assertTrue(hz.getMap("results").isEmpty());
        } else {
            // and can't connect to DB
            assertThat(job).eventuallyHasStatus(RUNNING);
            assertTrue(hz.getMap("results").isEmpty());

            // and DB starts
            postgres.start();
            try {
                // then source connects successfully
                assertEqualsEventually(() -> hz.getMap("results").size(), 4);
                assertEquals(RUNNING, job.getStatus());
            } finally {
                abortJob(job);
            }
        }
    }

    @Test
    public void when_shortNetworkDisconnectDuringSnapshotting_then_connectorDoesNotNoticeAnything() throws Exception {
        try (
                Network network = initNetwork();
                ToxiproxyContainer toxiproxy = initToxiproxy(network)
        ) {
            postgres = initPostgres(network, null);
            Proxy proxy = initProxy(toxiproxy);

            String host = toxiproxy.getHost();
            Integer port = toxiproxy.getMappedPort(8666);

            Pipeline pipeline = initPipeline(host, port);
            // when job starts
            HazelcastInstance hz = createHazelcastInstances(2)[0];
            Job job = hz.getJet().newJob(pipeline);
            assertThat(job).eventuallyHasStatus(RUNNING);

            // and snapshotting is ongoing (we have no exact way of identifying
            // the moment, but random sleep will catch it at least some of the time)
            MILLISECONDS.sleep(ThreadLocalRandom.current().nextInt(0, 500));

            // and connection is cut
            setConnectionCut(proxy, true);

            // and some time passes
            MILLISECONDS.sleep(2 * RECONNECT_INTERVAL_MS);
            //it takes the bloody thing ages to notice the connection being down, so it won't notice this...

            // and connection recovers
            setConnectionCut(proxy, false);

            // then connector manages to reconnect and finish snapshot
            try {
                assertEqualsEventually(() -> hz.getMap("results").size(), 4);
            } finally {
                abortJob(job);
            }
        }
    }

    @Test
    public void when_databaseShutdownOrLongDisconnectDuringSnapshotting() throws Exception {
        postgres = initPostgres(null, null);
        int port = fixPortBinding(postgres, POSTGRESQL_PORT);

        Pipeline pipeline = initPipeline(postgres.getHost(), port);
        // when job starts
        HazelcastInstance hz = createHazelcastInstances(2)[0];
        Job job = hz.getJet().newJob(pipeline);
        assertThat(job).eventuallyHasStatus(RUNNING);

        // and snapshotting is ongoing (we have no exact way of identifying
        // the moment, but random sleep will catch it at least some of the time)
        MILLISECONDS.sleep(ThreadLocalRandom.current().nextInt(100, 500));

        // and DB is stopped
        stopContainer(postgres);

        // then
        boolean neverReconnect = reconnectBehavior.getMaxAttempts() == 0;
        if (neverReconnect) {
            // then job fails
            assertThatThrownBy(job::join)
                    .hasCauseInstanceOf(JetException.class)
                    .hasStackTraceContaining("Failed to connect to database");
        } else {
            // and DB is started anew
            postgres = initPostgres(null, port);

            // then snapshotting finishes successfully
            try {
                assertEqualsEventually(() -> hz.getMap("results").size(), 4);
                assertEquals(RUNNING, job.getStatus());
            } finally {
                abortJob(job);
            }
        }
    }

    @Test
    public void when_shortConnectionLossDuringBinlogReading_then_connectorDoesNotNoticeAnything() throws Exception {
        try (
                Network network = initNetwork();
                ToxiproxyContainer toxiproxy = initToxiproxy(network)
        ) {
            postgres = initPostgres(network, null);
            Proxy proxy = initProxy(toxiproxy);

            String host = toxiproxy.getHost();
            Integer port = toxiproxy.getMappedPort(8666);

            Pipeline pipeline = initPipeline(host, port);
            // when connector is up and transitions to binlog reading
            HazelcastInstance hz = createHazelcastInstances(2)[0];
            Job job = hz.getJet().newJob(pipeline);
            assertEqualsEventually(() -> hz.getMap("results").size(), 4);
            SECONDS.sleep(3);
            insertRecords(postgres, 1005);
            assertEqualsEventually(() -> hz.getMap("results").size(), 5);

            // and the connection is cut
            setConnectionCut(proxy, true);

            // and some new events get generated in the DB
            insertRecords(postgres, 1006, 1007);

            // and some time passes
            MILLISECONDS.sleep(5 * RECONNECT_INTERVAL_MS);

            // and the connection is re-established
            setConnectionCut(proxy, false);

            // then
            try {
                // then job keeps running, connector starts freshly, including snapshotting
                assertEqualsEventually(() -> hz.getMap("results").size(), 7);
                assertEquals(RUNNING, job.getStatus());
            } finally {
                abortJob(job);
            }
        }
    }

    @Test
    public void when_databaseShutdownOrLongDisconnectDuringBinlogReading() throws Exception {
        Assume.assumeFalse(reconnectBehavior.getMaxAttempts() < 0 && !resetStateOnReconnect);

        postgres = initPostgres(null, null);
        int port = fixPortBinding(postgres, POSTGRESQL_PORT);

        Pipeline pipeline = initPipeline(postgres.getHost(), port);
        // when connector is up and transitions to binlog reading
        HazelcastInstance hz = createHazelcastInstances(2)[0];
        Job job = hz.getJet().newJob(pipeline);
        assertEqualsEventually(() -> hz.getMap("results").size(), 4);
        SECONDS.sleep(3);
        insertRecords(postgres, 1005);
        assertEqualsEventually(() -> hz.getMap("results").size(), 5);

        // and DB is stopped
        stopContainer(postgres);

        boolean neverReconnect = reconnectBehavior.getMaxAttempts() == 0;
        if (neverReconnect) {
            // then job fails
            assertThatThrownBy(job::join)
                    .hasCauseInstanceOf(JetException.class)
                    .hasStackTraceContaining("Failed to connect to database");
        } else {
            // and results are cleared
            hz.getMap("results").clear();
            assertEqualsEventually(() -> hz.getMap("results").size(), 0);

            // and DB is started anew
            postgres = initPostgres(null, port);
            insertRecords(postgres, 1005);

            // and some time passes
            SECONDS.sleep(3);
            insertRecords(postgres, 1006, 1007);

            try {
                // then job keeps running, connector starts freshly, including snapshotting
                assertEqualsEventually(() -> hz.getMap("results").size(), 7);
                assertEquals(RUNNING, job.getStatus());
            } finally {
                abortJob(job);
            }
        }
    }

    @Nonnull
    private StreamSource<ChangeRecord> source(String host, int port) {
        return PostgresCdcSources.postgres("customers")
                .setDatabaseAddress(host)
                .setDatabasePort(port)
                .setDatabaseUser("postgres")
                .setDatabasePassword("postgres")
                .setDatabaseName("postgres")
                .setTableWhitelist("inventory.customers")
                .setReconnectBehavior(reconnectBehavior)
                .setShouldStateBeResetOnReconnect(resetStateOnReconnect)
                .setSnapshotMode(INITIAL)
                .setCustomProperty("plugin.name", "pgoutput")
                .build();
    }

    private Pipeline initPipeline(String host, int port) {
        Pipeline pipeline = Pipeline.create();
        pipeline.readFrom(source(host, port))
                .withNativeTimestamps(0)
                .map(r -> entry(Objects.requireNonNull(r.key()).toMap().get("id"), r.value().toJson()))
                .writeTo(Sinks.map("results"));
        return pipeline;
    }

    private void abortJob(Job job) {
        try {
            job.cancel();
            job.join();
        } catch (Exception e) {
            // ignore, cancellation exception expected
        }
    }

    @SuppressWarnings("ConstantConditions")
    private PostgreSQLContainer<?> initPostgres(Network network, Integer fixedExposedPort) {
        PostgreSQLContainer<?> postgres = namedTestContainer(
                new PostgreSQLContainer<>(DEBEZIUM_POSTGRES_IMAGE)
                        .withDatabaseName("postgres")
                        .withUsername("postgres")
                        .withPassword("postgres")
        );
        if (fixedExposedPort != null) {
            Consumer<CreateContainerCmd> cmd = e -> e.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(fixedExposedPort), new ExposedPort(POSTGRESQL_PORT)));
            postgres = postgres.withCreateContainerCmdModifier(cmd);
        }
        if (network != null) {
            postgres = postgres.withNetwork(network)
                    .withNetworkAliases(NETWORK_ALIAS);
        }
        postgres.start();
        return postgres;
    }

    @SuppressWarnings("resource")
    private ToxiproxyContainer initToxiproxy(Network network) {
        ToxiproxyContainer toxiproxy = namedTestContainer(new ToxiproxyContainer(TOXIPROXY_IMAGE).withNetwork(network));
        toxiproxy.start();
        return toxiproxy;
    }

    private static Network initNetwork() {
        return Network.newNetwork();
    }

    private static Proxy initProxy(ToxiproxyContainer toxiproxy) throws IOException {
        ToxiproxyClient toxiproxyClient = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
        return toxiproxyClient.createProxy(NETWORK_ALIAS, "0.0.0.0:8666", UPSTREAM);
    }

    private void setConnectionCut(Proxy proxy, boolean shouldCutConnection) throws IOException {
        if (shouldCutConnection) {
            proxy.toxics().bandwidth(ToxicDirection.DOWNSTREAM.name(), ToxicDirection.DOWNSTREAM, 0);
            proxy.toxics().bandwidth(ToxicDirection.UPSTREAM.name(), ToxicDirection.UPSTREAM, 0);
        } else {
            proxy.toxics().get(ToxicDirection.DOWNSTREAM.name()).remove();
            proxy.toxics().get(ToxicDirection.UPSTREAM.name()).remove();
        }
    }

    private static void insertRecords(PostgreSQLContainer<?> postgres, int... ids) throws SQLException {
        try (Connection connection = getConnection(postgres)) {
            connection.setSchema("inventory");
            connection.setAutoCommit(false);
            Statement statement = connection.createStatement();
            for (int id : ids) {
                statement.addBatch("INSERT INTO customers VALUES (" + id + ", 'Jason', 'Bourne', " +
                        "'jason" + id + "@bourne.org')");
            }
            statement.executeBatch();
            connection.commit();
        }
    }

}
