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

package com.hazelcast.spi.impl;

import com.hazelcast.cluster.Address;
import com.hazelcast.cluster.impl.MemberImpl;
import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.WanReplicationRef;
import com.hazelcast.core.HazelcastException;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.dataconnection.impl.DataConnectionServiceImpl;
import com.hazelcast.dataconnection.impl.InternalDataConnectionService;
import com.hazelcast.instance.impl.Node;
import com.hazelcast.internal.cluster.ClusterService;
import com.hazelcast.internal.diagnostics.Diagnostics;
import com.hazelcast.internal.dynamicconfig.ClusterWideConfigurationService;
import com.hazelcast.internal.dynamicconfig.ConfigurationService;
import com.hazelcast.internal.management.ManagementCenterService;
import com.hazelcast.internal.metrics.MetricsRegistry;
import com.hazelcast.internal.metrics.impl.MetricsConfigHelper;
import com.hazelcast.internal.metrics.impl.MetricsRegistryImpl;
import com.hazelcast.internal.metrics.metricsets.ClassLoadingMetricSet;
import com.hazelcast.internal.metrics.metricsets.FileMetricSet;
import com.hazelcast.internal.metrics.metricsets.GarbageCollectionMetricSet;
import com.hazelcast.internal.metrics.metricsets.OperatingSystemMetricSet;
import com.hazelcast.internal.metrics.metricsets.RuntimeMetricSet;
import com.hazelcast.internal.metrics.metricsets.ThreadMetricSet;
import com.hazelcast.internal.namespace.UserCodeNamespaceService;
import com.hazelcast.internal.nio.Packet;
import com.hazelcast.internal.partition.InternalPartitionService;
import com.hazelcast.internal.partition.PartitionMigrationEvent;
import com.hazelcast.internal.partition.ReplicaSyncEvent;
import com.hazelcast.internal.serialization.Data;
import com.hazelcast.internal.serialization.SerializationService;
import com.hazelcast.internal.serialization.impl.compact.SchemaService;
import com.hazelcast.internal.serialization.impl.compact.schema.MemberSchemaService;
import com.hazelcast.internal.services.PostJoinAwareService;
import com.hazelcast.internal.services.PreJoinAwareService;
import com.hazelcast.internal.tpc.TpcServerBootstrap;
import com.hazelcast.internal.usercodedeployment.UserCodeDeploymentClassLoader;
import com.hazelcast.internal.usercodedeployment.UserCodeDeploymentService;
import com.hazelcast.internal.util.ConcurrencyDetection;
import com.hazelcast.internal.util.phonehome.PhoneHome;
import com.hazelcast.jet.impl.JetServiceBackend;
import com.hazelcast.jet.impl.util.Util;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.LoggingService;
import com.hazelcast.logging.impl.LoggingServiceImpl;
import com.hazelcast.spi.exception.RetryableHazelcastException;
import com.hazelcast.spi.exception.ServiceNotFoundException;
import com.hazelcast.spi.impl.eventservice.EventService;
import com.hazelcast.spi.impl.eventservice.impl.EventServiceImpl;
import com.hazelcast.spi.impl.executionservice.ExecutionService;
import com.hazelcast.spi.impl.executionservice.impl.ExecutionServiceImpl;
import com.hazelcast.spi.impl.operationparker.OperationParker;
import com.hazelcast.spi.impl.operationparker.impl.OperationParkerImpl;
import com.hazelcast.spi.impl.operationservice.Operation;
import com.hazelcast.spi.impl.operationservice.OperationService;
import com.hazelcast.spi.impl.operationservice.PartitionAwareOperation;
import com.hazelcast.spi.impl.operationservice.impl.OperationServiceImpl;
import com.hazelcast.spi.impl.proxyservice.InternalProxyService;
import com.hazelcast.spi.impl.proxyservice.impl.ProxyServiceImpl;
import com.hazelcast.spi.impl.servicemanager.ServiceInfo;
import com.hazelcast.spi.impl.servicemanager.ServiceManager;
import com.hazelcast.spi.impl.servicemanager.impl.ServiceManagerImpl;
import com.hazelcast.spi.impl.tenantcontrol.impl.TenantControlServiceImpl;
import com.hazelcast.spi.merge.NamespaceAwareSplitBrainMergePolicyProvider;
import com.hazelcast.spi.merge.SplitBrainMergePolicyProvider;
import com.hazelcast.spi.properties.ClusterProperty;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.splitbrainprotection.impl.SplitBrainProtectionServiceImpl;
import com.hazelcast.sql.impl.InternalSqlService;
import com.hazelcast.sql.impl.MissingSqlService;
import com.hazelcast.transaction.TransactionManagerService;
import com.hazelcast.transaction.impl.TransactionManagerServiceImpl;
import com.hazelcast.version.MemberVersion;
import com.hazelcast.wan.impl.WanReplicationService;

import javax.annotation.Nonnull;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static com.hazelcast.internal.config.MergePolicyValidator.checkMapMergePolicy;
import static com.hazelcast.internal.metrics.MetricDescriptorConstants.MEMORY_PREFIX;
import static com.hazelcast.internal.metrics.impl.MetricsConfigHelper.memberMetricsLevel;
import static com.hazelcast.internal.util.EmptyStatement.ignore;
import static com.hazelcast.internal.util.ExceptionUtil.rethrow;
import static com.hazelcast.spi.properties.ClusterProperty.BACKPRESSURE_ENABLED;
import static com.hazelcast.spi.properties.ClusterProperty.CONCURRENT_WINDOW_MS;

/**
 * The NodeEngineImpl is the where the construction of the Hazelcast dependencies take place. It can be
 * compared to a Spring ApplicationContext. It is fine that we refer to concrete types, and it is fine
 * that we cast to a concrete type within this class (e.g. to call shutdown). In an application context
 * you get exactly the same behavior.
 * <p>
 * But the crucial thing is that we don't want to leak concrete dependencies to the outside. For example
 * we don't leak {@link com.hazelcast.spi.impl.operationservice.impl.OperationServiceImpl} to the outside.
 */
@SuppressWarnings({"checkstyle:classdataabstractioncoupling", "checkstyle:classfanoutcomplexity", "checkstyle:methodcount"})
public class NodeEngineImpl implements NodeEngine {

    private final Node node;
    private final SerializationService serializationService;
    private final SerializationService compatibilitySerializationService;
    private final LoggingServiceImpl loggingService;
    private final ILogger logger;
    private final MetricsRegistryImpl metricsRegistry;
    private final PhoneHome phoneHome;
    private final ProxyServiceImpl proxyService;
    private final ServiceManagerImpl serviceManager;
    private final ExecutionServiceImpl executionService;
    private final OperationServiceImpl operationService;
    private final EventServiceImpl eventService;
    private final OperationParkerImpl operationParker;
    private final ClusterWideConfigurationService configurationService;
    private final TransactionManagerServiceImpl transactionManagerService;
    private final WanReplicationService wanReplicationService;
    private final Consumer<Packet> packetDispatcher;
    private final SplitBrainProtectionServiceImpl splitBrainProtectionService;
    private final InternalSqlService sqlService;
    private final Diagnostics diagnostics;
    private final SplitBrainMergePolicyProvider splitBrainMergePolicyProvider;
    private final ConcurrencyDetection concurrencyDetection;
    private final TenantControlServiceImpl tenantControlService;
    private final InternalDataConnectionService dataConnectionService;
    private final TpcServerBootstrap tpcServerBootstrap;

    @SuppressWarnings("checkstyle:executablestatementcount")
    public NodeEngineImpl(Node node) {
        this.node = node;
        try {
            this.serializationService = node.getSerializationService();
            this.compatibilitySerializationService = node.getCompatibilitySerializationService();
            this.concurrencyDetection = newConcurrencyDetection();
            this.loggingService = node.loggingService;
            this.logger = node.getLogger(NodeEngine.class.getName());
            this.metricsRegistry = newMetricRegistry(node);
            this.phoneHome = new PhoneHome(node);
            this.proxyService = new ProxyServiceImpl(this);
            this.serviceManager = new ServiceManagerImpl(this);
            this.executionService = new ExecutionServiceImpl(this);
            this.tenantControlService = new TenantControlServiceImpl(this);
            this.tpcServerBootstrap = node.getNodeExtension().createTpcServerBootstrap();
            this.operationService = new OperationServiceImpl(this);
            this.eventService = new EventServiceImpl(this);
            this.operationParker = new OperationParkerImpl(this);
            UserCodeDeploymentService userCodeDeploymentService = new UserCodeDeploymentService();
            this.configurationService = node.getNodeExtension().createService(ClusterWideConfigurationService.class, this);
            ClassLoader configClassLoader = node.getConfigClassLoader();
            if (configClassLoader instanceof UserCodeDeploymentClassLoader loader) {
                loader.setUserCodeDeploymentService(userCodeDeploymentService);
            }
            this.transactionManagerService = new TransactionManagerServiceImpl(this);
            this.wanReplicationService = node.getNodeExtension().createService(WanReplicationService.class);
            this.sqlService = createSqlService();
            this.dataConnectionService = new DataConnectionServiceImpl(node, configClassLoader);
            this.packetDispatcher = new PacketDispatcher(
                    logger,
                    operationService.getOperationExecutor(),
                    operationService.getInboundResponseHandlerSupplier().get(),
                    operationService.getInvocationMonitor(),
                    eventService,
                    getJetPacketConsumer()
            );
            this.splitBrainProtectionService = new SplitBrainProtectionServiceImpl(this);
            this.diagnostics = newDiagnostics();
            this.splitBrainMergePolicyProvider = getConfig().getNamespacesConfig().isEnabled()
                    ? new NamespaceAwareSplitBrainMergePolicyProvider(this)
                    : new SplitBrainMergePolicyProvider(this.getConfigClassLoader());

            checkMapMergePolicies(node);


            serviceManager.registerService(OperationService.SERVICE_NAME, operationService);
            serviceManager.registerService(OperationParker.SERVICE_NAME, operationParker);
            serviceManager.registerService(UserCodeDeploymentService.SERVICE_NAME, userCodeDeploymentService);
            serviceManager.registerService(SchemaService.SERVICE_NAME, node.getSchemaService());
            serviceManager.registerService(ConfigurationService.SERVICE_NAME, configurationService);
            serviceManager.registerService(TenantControlServiceImpl.SERVICE_NAME, tenantControlService);
        } catch (Throwable e) {
            try {
                shutdown(true);
            } catch (Throwable ignored) {
                ignore(ignored);
            }
            throw rethrow(e);
        }
    }

    private InternalSqlService createSqlService() {
        if (!Util.isJetEnabled(this)) {
            return new MissingSqlService(node.getThisUuid(), false);
        }
        Class<?> clz;
        try {
            clz = Class.forName("com.hazelcast.sql.impl.SqlServiceImpl");
        } catch (ClassNotFoundException e) {
            // this is normal if the hazelcast-sql module isn't present - return disabled service
            return new MissingSqlService(node.getThisUuid(), true);
        }

        try {
            Constructor<?> constructor = clz.getConstructor(getClass());
            return (InternalSqlService) constructor.newInstance(this);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException
                 | ClassCastException e) {
            // this isn't normal - we found the class, but there's something unexpected
            throw new RuntimeException(e);
        }
    }

    @Override
    public TpcServerBootstrap getTpcServerBootstrap() {
        return tpcServerBootstrap;
    }

    private void checkMapMergePolicies(Node node) {
        Map<String, MapConfig> mapConfigs = node.config.getMapConfigs();
        for (MapConfig mapConfig : mapConfigs.values()) {
            WanReplicationRef wanReplicationRef = mapConfig.getWanReplicationRef();
            if (wanReplicationRef != null) {
                String wanMergePolicyClassName = mapConfig.getWanReplicationRef().getMergePolicyClassName();
                checkMapMergePolicy(mapConfig,
                        wanMergePolicyClassName, splitBrainMergePolicyProvider);
            }

            String splitBrainMergePolicyClassName = mapConfig.getMergePolicyConfig().getPolicy();
            checkMapMergePolicy(mapConfig,
                    splitBrainMergePolicyClassName, splitBrainMergePolicyProvider);
        }
    }

    private ConcurrencyDetection newConcurrencyDetection() {
        HazelcastProperties properties = node.getProperties();
        boolean writeThrough = properties.getBoolean(ClusterProperty.IO_WRITE_THROUGH_ENABLED);
        boolean backPressureEnabled = properties.getBoolean(BACKPRESSURE_ENABLED);

        if (writeThrough || backPressureEnabled) {
            return ConcurrencyDetection.createEnabled(properties.getInteger(CONCURRENT_WINDOW_MS));
        } else {
            return ConcurrencyDetection.createDisabled();
        }
    }

    private MetricsRegistryImpl newMetricRegistry(Node node) {
        return new MetricsRegistryImpl(getHazelcastInstance().getName(), node.getLogger(MetricsRegistry.class),
                memberMetricsLevel(node.getProperties(), getLogger(MetricsConfigHelper.class)));
    }

    private Diagnostics newDiagnostics() {
        Address address = node.getThisAddress();
        String addressString = address.getHost().replace(":", "_") + "_" + address.getPort();
        String name = "diagnostics-" + addressString;

        return new Diagnostics(name, loggingService, getHazelcastInstance().getName(),
                node.getProperties(), this);
    }

    @Override
    public LoggingService getLoggingService() {
        return loggingService;
    }

    @Override
    public MetricsRegistry getMetricsRegistry() {
        return metricsRegistry;
    }

    @Override
    public PhoneHome getPhoneHome() {
        return phoneHome;
    }

    public void start() {
        RuntimeMetricSet.register(metricsRegistry);
        GarbageCollectionMetricSet.register(metricsRegistry);
        OperatingSystemMetricSet.register(metricsRegistry);
        ThreadMetricSet.register(metricsRegistry);
        ClassLoadingMetricSet.register(metricsRegistry);
        FileMetricSet.register(metricsRegistry);

        metricsRegistry.registerStaticMetrics(node.getNodeExtension().getMemoryStats(), MEMORY_PREFIX);
        metricsRegistry.provideMetrics(operationService, proxyService, eventService, operationParker);

        serviceManager.start();
        proxyService.init();
        operationService.start();
        splitBrainProtectionService.start();
        sqlService.start();
        tpcServerBootstrap.start();
        diagnostics.start();
        node.getNodeExtension().registerPlugins(diagnostics);
    }

    public ConcurrencyDetection getConcurrencyDetection() {
        return concurrencyDetection;
    }

    public Consumer<Packet> getPacketDispatcher() {
        return packetDispatcher;
    }

    public Diagnostics getDiagnostics() {
        return diagnostics;
    }

    public ClusterWideConfigurationService getConfigurationService() {
        return configurationService;
    }

    public ServiceManager getServiceManager() {
        return serviceManager;
    }

    @Override
    public Address getThisAddress() {
        return node.getThisAddress();
    }

    @Override
    public Address getMasterAddress() {
        return node.getMasterAddress();
    }

    @Override
    public MemberImpl getLocalMember() {
        return node.getLocalMember();
    }

    @Override
    public Config getConfig() {
        return node.getConfig();
    }

    @Override
    public ClassLoader getConfigClassLoader() {
        return node.getConfigClassLoader();
    }

    @Override
    public EventService getEventService() {
        return eventService;
    }

    @Override
    public SerializationService getSerializationService() {
        return serializationService;
    }

    @Override
    public SerializationService getCompatibilitySerializationService() {
        return compatibilitySerializationService;
    }

    @Override
    public OperationServiceImpl getOperationService() {
        return operationService;
    }

    @Override
    public ExecutionService getExecutionService() {
        return executionService;
    }

    @Override
    public InternalPartitionService getPartitionService() {
        return node.getPartitionService();
    }

    @Override
    public ClusterService getClusterService() {
        return node.getClusterService();
    }

    @Override
    public ManagementCenterService getManagementCenterService() {
        return node.getManagementCenterService();
    }

    @Override
    public InternalProxyService getProxyService() {
        return proxyService;
    }

    @Override
    public TenantControlServiceImpl getTenantControlService() {
        return tenantControlService;
    }

    public OperationParker getOperationParker() {
        return operationParker;
    }

    @Override
    public WanReplicationService getWanReplicationService() {
        return wanReplicationService;
    }

    @Override
    public SplitBrainProtectionServiceImpl getSplitBrainProtectionService() {
        return splitBrainProtectionService;
    }

    @Override
    public InternalSqlService getSqlService() {
        return sqlService;
    }

    @Override
    public InternalDataConnectionService getDataConnectionService() {
        return dataConnectionService;
    }

    @Override
    public TransactionManagerService getTransactionManagerService() {
        return transactionManagerService;
    }

    @Override
    public Data toData(Object object) {
        return serializationService.toData(object);
    }

    @Override
    public <T> T toObject(Object object) {
        return serializationService.toObject(object);
    }

    @Override
    public <T> T toObject(Object object, Class klazz) {
        return serializationService.toObject(object, klazz);
    }

    @Override
    public boolean isRunning() {
        return node.isRunning();
    }

    @Override
    public boolean isStartCompleted() {
        return node.getNodeExtension().isStartCompleted();
    }

    @Override
    public HazelcastInstance getHazelcastInstance() {
        return node.hazelcastInstance;
    }

    @Override
    public ILogger getLogger(String name) {
        return loggingService.getLogger(name);
    }

    @Override
    public ILogger getLogger(Class clazz) {
        return loggingService.getLogger(clazz);
    }

    @Override
    public HazelcastProperties getProperties() {
        return node.getProperties();
    }

    @Override
    public <T> T getService(@Nonnull String serviceName) {
        T service = serviceManager.getService(serviceName);
        if (service == null) {
            if (isRunning()) {
                throw new HazelcastException("Service with name '" + serviceName + "' not found!",
                        new ServiceNotFoundException("Service with name '" + serviceName + "' not found!"));
            } else {
                throw new RetryableHazelcastException("HazelcastInstance[" + getThisAddress() + "] is not active!");
            }
        }
        return service;
    }

    @Override
    public <T> T getServiceOrNull(@Nonnull String serviceName) {
        return serviceManager.getService(serviceName);
    }

    @Override
    public MemberVersion getVersion() {
        return node.getVersion();
    }

    @Override
    public SplitBrainMergePolicyProvider getSplitBrainMergePolicyProvider() {
        return splitBrainMergePolicyProvider;
    }

    @Override
    public <S> Collection<S> getServices(Class<S> serviceClass) {
        return serviceManager.getServices(serviceClass);
    }

    public Collection<ServiceInfo> getServiceInfos(Class serviceClass) {
        return serviceManager.getServiceInfos(serviceClass);
    }

    public void forEachMatchingService(Class serviceClass, Consumer<ServiceInfo> consumer) {
        serviceManager.forEachMatchingService(serviceClass, consumer);
    }

    @Override
    public Node getNode() {
        return node;
    }

    public void onMemberLeft(MemberImpl member) {
        operationParker.onMemberLeft(member);
        operationService.onMemberLeft(member);
        eventService.onMemberLeft(member);
    }

    @Override
    public void onClientDisconnected(UUID clientUuid) {
        operationParker.onClientDisconnected(clientUuid);
    }

    public void onPartitionMigrate(PartitionMigrationEvent migrationInfo) {
        operationParker.onPartitionMigrate(migrationInfo);
    }

    public void onReplicaSync(ReplicaSyncEvent syncEvent) {
        operationParker.onReplicaSync(syncEvent);
    }

    /**
     * Collects all post-join operations from {@link PostJoinAwareService}s.
     * <p>
     * Post join operations should return response, at least a {@code null} response.
     * <p>
     * <b>Note</b>: Post join operations must be lock free, meaning no locks at all:
     * no partition locks, no key-based locks, no service level locks, no database interaction!
     * The {@link Operation#getPartitionId()} method should return a negative value.
     * This means that the operations should not implement {@link PartitionAwareOperation}.
     *
     * @return the operations to be executed at the end of a finalized join
     */
    public Collection<Operation> getPostJoinOperations() {
        Collection<Operation> postJoinOps = new LinkedList<>();
        Collection<PostJoinAwareService> services = getServices(PostJoinAwareService.class);
        for (PostJoinAwareService service : services) {
            Operation postJoinOperation = service.getPostJoinOperation();
            if (postJoinOperation != null) {
                if (postJoinOperation.getPartitionId() >= 0) {
                    logger.severe("Post-join operations should not have partition ID set! Service: "
                            + service + ", Operation: "
                            + postJoinOperation);
                    continue;
                }
                postJoinOps.add(postJoinOperation);
            }
        }
        return postJoinOps;
    }

    public Collection<Operation> getPreJoinOperations() {
        Collection<Operation> preJoinOps = new LinkedList<>();
        Collection<PreJoinAwareService> services = getServices(PreJoinAwareService.class);
        for (PreJoinAwareService service : services) {
            Operation preJoinOperation = service.getPreJoinOperation();
            if (preJoinOperation != null) {
                if (preJoinOperation.getPartitionId() >= 0) {
                    logger.severe("Pre-join operations operations should not have partition ID set! Service: "
                            + service + ", Operation: "
                            + preJoinOperation);
                    continue;
                }
                preJoinOps.add(preJoinOperation);
            }
        }
        return preJoinOps;
    }

    public void reset() {
        sqlService.reset();
        operationParker.reset();
        operationService.reset();
    }

    @SuppressWarnings({"checkstyle:npathcomplexity", "cyclomaticcomplexity"})
    public void shutdown(boolean terminate) {
        logger.finest("Shutting down services...");
        if (sqlService != null) {
            sqlService.shutdown();
        }

        if (operationParker != null) {
            operationParker.shutdown();
        }
        if (operationService != null) {
            operationService.shutdownInvocations();
        }
        if (proxyService != null) {
            proxyService.shutdown();
        }
        if (serviceManager != null) {
            serviceManager.shutdown(terminate);
        }
        if (eventService != null) {
            eventService.shutdown();
        }
        if (operationService != null) {
            operationService.shutdownOperationExecutor();
        }
        if (wanReplicationService != null) {
            wanReplicationService.shutdown();
        }
        if (executionService != null) {
            executionService.shutdown();
        }
        if (tpcServerBootstrap != null) {
            tpcServerBootstrap.shutdown();
        }
        if (metricsRegistry != null) {
            metricsRegistry.shutdown();
        }
        if (phoneHome != null) {
            phoneHome.shutdown();
        }
        if (diagnostics != null) {
            diagnostics.shutdown();
        }
        if (dataConnectionService != null) {
            dataConnectionService.shutdown();
        }

    }

    @Override
    public MemberSchemaService getSchemaService() {
        return node.getSchemaService();
    }

    @Nonnull
    private Consumer<Packet> getJetPacketConsumer() {
        // Here, JetServiceBackend is not registered to service manager yet
        JetServiceBackend jetServiceBackend = node.getNodeExtension().getJetServiceBackend();
        if (jetServiceBackend != null) {
            return jetServiceBackend;
        } else {
            return packet -> {
                throw new UnsupportedOperationException("Jet is not enabled on this node");
            };
        }
    }

    @Override
    public UserCodeNamespaceService getNamespaceService() {
        return node.getNamespaceService();
    }
}
