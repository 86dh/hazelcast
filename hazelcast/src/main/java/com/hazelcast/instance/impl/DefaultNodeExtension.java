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

package com.hazelcast.instance.impl;

import com.hazelcast.auditlog.AuditlogService;
import com.hazelcast.auditlog.impl.NoOpAuditlogService;
import com.hazelcast.cache.impl.CacheService;
import com.hazelcast.cache.impl.ICacheService;
import com.hazelcast.client.impl.ClientEngine;
import com.hazelcast.client.impl.ClientEngineImpl;
import com.hazelcast.client.impl.ClusterViewListenerService;
import com.hazelcast.cluster.ClusterState;
import com.hazelcast.config.AuditlogConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.InstanceTrackingConfig;
import com.hazelcast.config.InstanceTrackingConfig.InstanceMode;
import com.hazelcast.config.InstanceTrackingConfig.InstanceProductName;
import com.hazelcast.config.InvalidConfigurationException;
import com.hazelcast.config.PersistenceConfig;
import com.hazelcast.config.SSLConfig;
import com.hazelcast.config.SecurityConfig;
import com.hazelcast.config.SerializationConfig;
import com.hazelcast.config.SymmetricEncryptionConfig;
import com.hazelcast.config.cp.CPSubsystemConfig;
import com.hazelcast.core.HazelcastInstanceAware;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import com.hazelcast.cp.CPSubsystem;
import com.hazelcast.cp.CPSubsystemStubImpl;
import com.hazelcast.cp.internal.persistence.CPPersistenceService;
import com.hazelcast.hotrestart.HotRestartService;
import com.hazelcast.instance.BuildInfo;
import com.hazelcast.instance.BuildInfoProvider;
import com.hazelcast.instance.EndpointQualifier;
import com.hazelcast.internal.ascii.TextCommandService;
import com.hazelcast.internal.ascii.TextCommandServiceImpl;
import com.hazelcast.internal.cluster.ClusterStateListener;
import com.hazelcast.internal.cluster.ClusterVersionListener;
import com.hazelcast.internal.cluster.impl.JoinMessage;
import com.hazelcast.internal.cluster.impl.JoinRequest;
import com.hazelcast.internal.cluster.impl.SplitBrainJoinMessage;
import com.hazelcast.internal.cluster.impl.VersionMismatchException;
import com.hazelcast.internal.diagnostics.BuildInfoPlugin;
import com.hazelcast.internal.diagnostics.ConfigPropertiesPlugin;
import com.hazelcast.internal.diagnostics.Diagnostics;
import com.hazelcast.internal.diagnostics.EventQueuePlugin;
import com.hazelcast.internal.diagnostics.HealthMonitor;
import com.hazelcast.internal.diagnostics.InvocationProfilerPlugin;
import com.hazelcast.internal.diagnostics.InvocationSamplePlugin;
import com.hazelcast.internal.diagnostics.MemberHazelcastInstanceInfoPlugin;
import com.hazelcast.internal.diagnostics.MemberHeartbeatPlugin;
import com.hazelcast.internal.diagnostics.MetricsPlugin;
import com.hazelcast.internal.diagnostics.NetworkingImbalancePlugin;
import com.hazelcast.internal.diagnostics.OperationHeartbeatPlugin;
import com.hazelcast.internal.diagnostics.OperationProfilerPlugin;
import com.hazelcast.internal.diagnostics.OperationThreadSamplerPlugin;
import com.hazelcast.internal.diagnostics.OverloadedConnectionsPlugin;
import com.hazelcast.internal.diagnostics.PendingInvocationsPlugin;
import com.hazelcast.internal.diagnostics.SlowOperationPlugin;
import com.hazelcast.internal.diagnostics.StoreLatencyPlugin;
import com.hazelcast.internal.diagnostics.SystemLogPlugin;
import com.hazelcast.internal.diagnostics.SystemPropertiesPlugin;
import com.hazelcast.internal.dynamicconfig.ClusterWideConfigurationService;
import com.hazelcast.internal.dynamicconfig.EmptyDynamicConfigListener;
import com.hazelcast.internal.hotrestart.InternalHotRestartService;
import com.hazelcast.internal.hotrestart.NoOpHotRestartService;
import com.hazelcast.internal.hotrestart.NoopInternalHotRestartService;
import com.hazelcast.internal.jmx.ManagementService;
import com.hazelcast.internal.management.TimedMemberStateFactory;
import com.hazelcast.internal.memory.DefaultMemoryStats;
import com.hazelcast.internal.memory.MemoryStats;
import com.hazelcast.internal.namespace.UserCodeNamespaceService;
import com.hazelcast.internal.namespace.impl.NoOpUserCodeNamespaceService;
import com.hazelcast.internal.namespace.impl.NodeEngineThreadLocalContext;
import com.hazelcast.internal.networking.ChannelInitializer;
import com.hazelcast.internal.networking.InboundHandler;
import com.hazelcast.internal.networking.OutboundHandler;
import com.hazelcast.internal.nio.ClassLoaderUtil;
import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.internal.serialization.SerializationServiceBuilder;
import com.hazelcast.internal.serialization.impl.CodebaseClusterVersionAware;
import com.hazelcast.internal.serialization.impl.DefaultSerializationServiceBuilder;
import com.hazelcast.internal.serialization.impl.compact.schema.MemberSchemaService;
import com.hazelcast.internal.server.ServerConnection;
import com.hazelcast.internal.server.ServerContext;
import com.hazelcast.internal.server.tcp.ChannelInitializerFunction;
import com.hazelcast.internal.server.tcp.PacketDecoder;
import com.hazelcast.internal.server.tcp.PacketEncoder;
import com.hazelcast.internal.tpc.TpcServerBootstrap;
import com.hazelcast.internal.tpc.TpcServerBootstrapImpl;
import com.hazelcast.internal.util.ConstructorFunction;
import com.hazelcast.internal.util.JVMUtil;
import com.hazelcast.internal.util.MapUtil;
import com.hazelcast.internal.util.Preconditions;
import com.hazelcast.jet.JetService;
import com.hazelcast.jet.config.JetConfig;
import com.hazelcast.jet.impl.JetServiceBackend;
import com.hazelcast.jet.impl.JobEventService;
import com.hazelcast.logging.ILogger;
import com.hazelcast.map.impl.MapService;
import com.hazelcast.nio.MemberSocketInterceptor;
import com.hazelcast.nio.ssl.SSLEngineFactory;
import com.hazelcast.partition.PartitioningStrategy;
import com.hazelcast.partition.strategy.DefaultPartitioningStrategy;
import com.hazelcast.security.SecurityContext;
import com.hazelcast.spi.impl.NodeEngine;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.spi.impl.eventservice.impl.EventServiceImpl;
import com.hazelcast.spi.impl.servicemanager.ServiceManager;
import com.hazelcast.spi.properties.ClusterProperty;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.version.MemberVersion;
import com.hazelcast.version.Version;
import com.hazelcast.wan.impl.WanReplicationService;
import com.hazelcast.wan.impl.WanReplicationServiceImpl;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static com.hazelcast.config.ConfigAccessor.getActiveMemberNetworkConfig;
import static com.hazelcast.config.InstanceTrackingConfig.InstanceTrackingProperties.LICENSED;
import static com.hazelcast.config.InstanceTrackingConfig.InstanceTrackingProperties.MODE;
import static com.hazelcast.config.InstanceTrackingConfig.InstanceTrackingProperties.PID;
import static com.hazelcast.config.InstanceTrackingConfig.InstanceTrackingProperties.PRODUCT;
import static com.hazelcast.config.InstanceTrackingConfig.InstanceTrackingProperties.START_TIMESTAMP;
import static com.hazelcast.config.InstanceTrackingConfig.InstanceTrackingProperties.VERSION;
import static com.hazelcast.cp.CPSubsystemStubImpl.CP_SUBSYSTEM_IS_NOT_AVAILABLE_IN_OS_MEMBERS;
import static com.hazelcast.internal.util.CollectionUtil.setOf;
import static com.hazelcast.internal.util.ExceptionUtil.rethrow;
import static com.hazelcast.internal.util.InstanceTrackingUtil.writeInstanceTrackingFile;
import static com.hazelcast.internal.util.StringUtil.isNullOrEmpty;
import static com.hazelcast.jet.impl.util.Util.JET_IS_DISABLED_MESSAGE;
import static com.hazelcast.jet.impl.util.Util.checkJetIsEnabled;
import static com.hazelcast.map.impl.MapServiceConstructor.getDefaultMapServiceConstructor;

@SuppressWarnings({"checkstyle:methodcount", "checkstyle:classfanoutcomplexity", "checkstyle:classdataabstractioncoupling"})
public class DefaultNodeExtension implements NodeExtension {
    private static final String PLATFORM_LOGO = """
      o    o     o     o---o   o--o o      o---o     o     o----o o--o--o
      |    |    / \\       /         |     /         / \\    |         |
      o----o       o     o   o----o |    o             o   o----o    |
      |    |  *     \\   /           |     \\       *     \\       |    |
      o    o *       o o---o   o--o o----o o---o *       o o----o    o
      """.indent(4);

    private static final String COPYRIGHT_LINE = "Copyright (c) 2008-2025, Hazelcast, Inc. All Rights Reserved.";

    protected final Node node;
    protected final ILogger logger;
    protected final ILogger logoLogger;
    protected final ILogger systemLogger;
    protected final List<ClusterVersionListener> clusterVersionListeners = new CopyOnWriteArrayList<>();
    protected JetServiceBackend jetServiceBackend;
    protected IntegrityChecker integrityChecker;

    private final MemoryStats memoryStats = new DefaultMemoryStats();
    private final Set<Version> supportedVersions;

    public DefaultNodeExtension(Node node) {
        this.node = node;
        this.logger = node.getLogger(NodeExtension.class);
        this.logoLogger = node.getLogger("com.hazelcast.system.logo");
        this.systemLogger = node.getLogger("com.hazelcast.system");

        checkCPSubsystemAllowed();
        checkSecurityAllowed();
        checkPersistenceAllowed();
        checkLosslessRestartAllowed();
        checkDynamicConfigurationPersistenceAllowed();
        checkSqlCatalogPersistenceAllowed();

        if (node.getConfig().getJetConfig().isEnabled()) {
            jetServiceBackend = createService(JetServiceBackend.class);
        }

        integrityChecker = new IntegrityChecker(node.getConfig().getIntegrityCheckerConfig(), this.systemLogger);
        supportedVersions = setOf(BuildInfoProvider.getBuildInfo().getCodebaseVersion().asVersion());
    }

    private void checkCPSubsystemAllowed() {
        CPSubsystemConfig cpSubsystemConfig = node.getConfig().getCPSubsystemConfig();
        if (cpSubsystemConfig != null && cpSubsystemConfig.getCPMemberCount() != 0
                && !BuildInfoProvider.getBuildInfo().isEnterprise()) {
            throw new IllegalStateException(CP_SUBSYSTEM_IS_NOT_AVAILABLE_IN_OS_MEMBERS);
        }
    }

    private void checkPersistenceAllowed() {
        PersistenceConfig persistenceConfig = node.getConfig().getPersistenceConfig();
        if (persistenceConfig != null && persistenceConfig.isEnabled() && !BuildInfoProvider.getBuildInfo().isEnterprise()) {
            throw new IllegalStateException("Hot Restart requires Hazelcast Enterprise Edition");
        }
    }

    private void checkSecurityAllowed() {
        SecurityConfig securityConfig = node.getConfig().getSecurityConfig();
        if (securityConfig != null && securityConfig.isEnabled() && !BuildInfoProvider.getBuildInfo().isEnterprise()) {
            throw new IllegalStateException("Security requires Hazelcast Enterprise Edition");
        }
        SymmetricEncryptionConfig symmetricEncryptionConfig =
                getActiveMemberNetworkConfig(node.getConfig()).getSymmetricEncryptionConfig();
        if (symmetricEncryptionConfig != null && symmetricEncryptionConfig.isEnabled()
                && !BuildInfoProvider.getBuildInfo().isEnterprise()) {
            throw new IllegalStateException("Symmetric Encryption requires Hazelcast Enterprise Edition");
        }
        AuditlogConfig auditlogConfig = node.getConfig().getAuditlogConfig();
        if (auditlogConfig.isEnabled() && !BuildInfoProvider.getBuildInfo().isEnterprise()) {
            throw new IllegalStateException("Auditlog requires Hazelcast Enterprise Edition");
        }
    }

    private void checkLosslessRestartAllowed() {
        JetConfig jetConfig = node.getConfig().getJetConfig();
        if (jetConfig.isLosslessRestartEnabled() && !BuildInfoProvider.getBuildInfo().isEnterprise()) {
            throw new IllegalStateException("Lossless Restart requires Hazelcast Enterprise Edition");
        }
    }

    protected void checkDynamicConfigurationPersistenceAllowed() {
        Config config = node.getConfig();
        if (config.getDynamicConfigurationConfig().isPersistenceEnabled()) {
            if (!BuildInfoProvider.getBuildInfo().isEnterprise()) {
                throw new IllegalStateException("Dynamic Configuration Persistence requires Hazelcast Enterprise Edition");
            }

            if (config.getConfigurationFile() == null || !config.getConfigurationFile().exists()) {
                throw new InvalidConfigurationException(
                        "Dynamic Configuration Persistence is enabled but config file couldn't be found."
                                + " This is probably because declarative configuration isn't used."
                );
            }
        }
    }

    protected void checkSqlCatalogPersistenceAllowed() {
        Config config = node.getConfig();
        if (config.getSqlConfig().isCatalogPersistenceEnabled() && !BuildInfoProvider.getBuildInfo().isEnterprise()) {
            throw new IllegalStateException("SQL Catalog Persistence requires Hazelcast Enterprise Edition");
        }
    }

    @Override
    public void beforeStart() {
        integrityChecker.checkIntegrity();

        if (jetServiceBackend != null) {
            systemLogger.info("Jet is enabled");
            // Configure the internal distributed objects.
            jetServiceBackend.configureJetInternalObjects(node.config.getStaticConfig(), node.getProperties());
        } else {
            systemLogger.info(JET_IS_DISABLED_MESSAGE);
        }
    }

    @Override
    public void printNodeInfo() {
        BuildInfo buildInfo = node.getBuildInfo();
        printBannersBeforeNodeInfo();
        printNodeInfoInternal(buildInfo);
    }

    @Override
    public void logInstanceTrackingMetadata() {
        InstanceTrackingConfig trackingConfig = node.getConfig().getInstanceTrackingConfig();
        if (trackingConfig.isEnabled()) {
            writeInstanceTrackingFile(trackingConfig.getFileName(), trackingConfig.getFormatPattern(),
                    getTrackingFileProperties(node.getBuildInfo()), systemLogger);
        }
    }

    /**
     * Returns a map with supported instance tracking properties.
     *
     * @param buildInfo this node's build information
     */
    @SuppressWarnings("checkstyle:magicnumber")
    protected Map<String, Object> getTrackingFileProperties(BuildInfo buildInfo) {
        Map<String, Object> props = MapUtil.createHashMap(6);
        props.put(PRODUCT.getPropertyName(), InstanceProductName.HAZELCAST.getProductName());
        props.put(VERSION.getPropertyName(), buildInfo.getVersion());
        props.put(MODE.getPropertyName(), Boolean.getBoolean("hazelcast.tracking.server")
                ? InstanceMode.SERVER.getModeName()
                : InstanceMode.EMBEDDED.getModeName());
        props.put(START_TIMESTAMP.getPropertyName(), System.currentTimeMillis());
        props.put(LICENSED.getPropertyName(), 0);
        props.put(PID.getPropertyName(), JVMUtil.getPid());
        return props;
    }

    protected void printBannersBeforeNodeInfo() {
        logoLogger.info('\n' + PLATFORM_LOGO);
        systemLogger.info(COPYRIGHT_LINE);
    }

    private void printNodeInfoInternal(BuildInfo buildInfo) {
        systemLogger.info(getEditionString() + " " + buildInfo.getVersion()
                + " (" + buildInfo.toBuildString() + ") starting at " + node.getThisAddress());
        systemLogger.info("Cluster name: " + node.getConfig().getClusterName());
        systemLogger.fine("Configured Hazelcast Serialization version: %s", buildInfo.getSerializationVersion());
    }

    protected String getEditionString() {
        return "Hazelcast Platform";
    }

    @Override
    public void afterStart() {
        if (jetServiceBackend != null) {
            jetServiceBackend.startScanningForJobs();
        }
    }

    @Override
    public boolean isStartCompleted() {
        return node.getClusterService().isJoined();
    }

    @Override
    public boolean isReady() {
        return node.getClusterService().isJoined();
    }

    @Override
    public SecurityContext getSecurityContext() {
        logger.warning("Security features are only available on Hazelcast Enterprise!");
        return null;
    }

    @Override
    public InternalSerializationService createSerializationService() {
        return createSerializationService(false);
    }

    @Override
    public InternalSerializationService createCompatibilitySerializationService() {
        return createSerializationService(true);
    }

    @Override
    public MemberSchemaService createSchemaService() {
        return new MemberSchemaService();
    }

    /**
     * Creates a serialization service. The {@code isCompatibility} parameter defines
     * whether the serialization format used by the service will conform to the
     * 3.x or the 4.x format.
     *
     * @param isCompatibility {@code true} if the serialized format should conform to the
     *                 3.x serialization format, {@code false} otherwise
     * @return the serialization service
     */
    private InternalSerializationService createSerializationService(boolean isCompatibility) {
        InternalSerializationService ss;
        try {
            Config config = node.getConfig();
            ClassLoader configClassLoader = node.getConfigClassLoader();

            HazelcastInstanceImpl hazelcastInstance = node.hazelcastInstance;
            PartitioningStrategy<?> partitioningStrategy = getPartitioningStrategy(configClassLoader);

            SerializationServiceBuilder builder = new DefaultSerializationServiceBuilder();
            SerializationConfig serializationConfig = config.getSerializationConfig() != null
                    ? config.getSerializationConfig() : new SerializationConfig();

            byte version = (byte) node.getProperties().getInteger(ClusterProperty.SERIALIZATION_VERSION);

            ss = builder.setClassLoader(configClassLoader)
                    .setConfig(serializationConfig)
                    .setManagedContext(hazelcastInstance.managedContext)
                    .setPartitioningStrategy(partitioningStrategy)
                    .setHazelcastInstance(hazelcastInstance)
                    .setVersion(version)
                    .setSchemaService(node.getSchemaService())
                    .setNotActiveExceptionSupplier(HazelcastInstanceNotActiveException::new)
                    .setVersionedSerializationEnabled(true)
                    .setClusterVersionAware(new CodebaseClusterVersionAware())
                    .isCompatibility(isCompatibility)
                    .build();
        } catch (Exception e) {
            throw rethrow(e);
        }
        return ss;
    }

    protected PartitioningStrategy getPartitioningStrategy(ClassLoader configClassLoader) throws Exception {
        String partitioningStrategyClassName = node.getProperties().getString(ClusterProperty.PARTITIONING_STRATEGY_CLASS);
        if (!isNullOrEmpty(partitioningStrategyClassName)) {
            return ClassLoaderUtil.newInstance(configClassLoader, partitioningStrategyClassName);
        } else {
            return new DefaultPartitioningStrategy();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T createService(Class<T> clazz, Object... params) {
        if (WanReplicationService.class.isAssignableFrom(clazz)) {
            return (T) new WanReplicationServiceImpl(node);
        } else if (ICacheService.class.isAssignableFrom(clazz)) {
            return (T) new CacheService();
        } else if (MapService.class.isAssignableFrom(clazz)) {
            return createMapService();
        } else if (JetServiceBackend.class.isAssignableFrom(clazz)) {
            return (T) new JetServiceBackend(node);
        } else if (ClusterWideConfigurationService.class.isAssignableFrom(clazz)) {
            return createConfigurationService(params[0]);
        }

        throw new IllegalArgumentException("Unknown service class: " + clazz);
    }

    @SuppressWarnings("unchecked")
    private <T> T createMapService() {
        ConstructorFunction<NodeEngine, MapService> constructor = getDefaultMapServiceConstructor();
        NodeEngineImpl nodeEngine = node.getNodeEngine();
        return (T) constructor.createNew(nodeEngine);
    }

    @SuppressWarnings("unchecked")
    private <T> T createConfigurationService(Object nodeEngine) {
        if (!(nodeEngine instanceof NodeEngine)) {
            throw new IllegalArgumentException(
                    "While creating ConfigurationService expected NodeEngine as a parameter, but found: "
                            + nodeEngine.getClass().getName()
            );
        }
        return (T) new ClusterWideConfigurationService((NodeEngine) nodeEngine, new EmptyDynamicConfigListener());
    }

    @Override
    public Map<String, Object> createExtensionServices() {
        if (jetServiceBackend != null) {
            Map<String, Object> services = new HashMap<>();
            services.put(JetServiceBackend.SERVICE_NAME, jetServiceBackend);
            services.put(JobEventService.SERVICE_NAME, new JobEventService(node.getNodeEngine()));
            return services;
        }
        return Collections.emptyMap();
    }

    @Override
    public MemberSocketInterceptor getSocketInterceptor(EndpointQualifier endpointQualifier) {
        logger.warning("SocketInterceptor feature is only available on Hazelcast Enterprise!");
        return null;
    }

    @Override
    public InboundHandler[] createInboundHandlers(EndpointQualifier qualifier,
                                                  ServerConnection connection, ServerContext serverContext) {
        NodeEngineImpl nodeEngine = node.nodeEngine;
        PacketDecoder decoder = new PacketDecoder(connection, nodeEngine.getPacketDispatcher());
        return new InboundHandler[]{decoder};
    }

    @Override
    public OutboundHandler[] createOutboundHandlers(EndpointQualifier qualifier,
                                                    ServerConnection connection, ServerContext serverContext) {
        return new OutboundHandler[]{new PacketEncoder()};
    }

    @Override
    public Function<EndpointQualifier, ChannelInitializer> createChannelInitializerFn(ServerContext serverContext) {
        ChannelInitializerFunction provider = new ChannelInitializerFunction(serverContext, node.getConfig());
        provider.init();
        return provider;
    }

    @Override
    public MemoryStats getMemoryStats() {
        return memoryStats;
    }

    @Override
    public void beforeShutdown(boolean terminate) {
        if (jetServiceBackend != null && !terminate) {
            // shutdown jobs on graceful shutdown
            jetServiceBackend.shutDownJobs();
        }
    }

    @Override
    public void afterShutdown() {
        logger.info("Destroying node NodeExtension.");
    }

    @Override
    public void validateJoinRequest(JoinMessage joinMessage) {
        // check joining member's version is in defined supported versions.
        MemberVersion memberVersion = joinMessage.getMemberVersion();
        Version clusterVersion = node.getClusterService().getClusterVersion();
        if (joinMessage instanceof JoinRequest joinRequest && joinRequest.supportsVersion(clusterVersion)) {
            return;
        }
        // for split brain healing
        if (joinMessage instanceof SplitBrainJoinMessage && memberVersion.asVersion().equals(clusterVersion)) {
            return;
        }
        String msg = constructVersionMismatchMessage(memberVersion, clusterVersion);
        throw new VersionMismatchException(msg);
    }

    @Override
    public Set<Version> getSupportedVersions() {
        return supportedVersions;
    }

    private static String constructVersionMismatchMessage(MemberVersion memberVersion, Version clusterVersion) {
        String msg = "Joining node's version " + memberVersion + " is not compatible with cluster version " + clusterVersion;
        if (!BuildInfoProvider.getBuildInfo().isEnterprise()) {
            msg += " (Rolling Member Upgrades are only supported in Hazelcast Enterprise)";
        } else {
            msg += " (Rolling Member Upgrades is not licensed)";
        }
        return msg;
    }

    @Override
    public void beforeClusterStateChange(ClusterState currState, ClusterState requestedState, boolean isTransient) {
        if (jetServiceBackend != null) {
            jetServiceBackend.beforeClusterStateChange(requestedState);
        }
    }

    @Override
    public void onClusterStateChange(ClusterState newState, boolean isTransient) {
        ServiceManager serviceManager = node.getNodeEngine().getServiceManager();
        serviceManager.getServices(ClusterStateListener.class).forEach(listener -> listener.onClusterStateChange(newState));
    }

    @Override
    public void onPartitionStateChange() {
        ClusterViewListenerService service = node.clientEngine.getClusterViewListenerService();
        if (service != null) {
            service.onPartitionStateChange();
        }
    }

    @Override
    public void onMemberListChange() {
        ClusterViewListenerService service = node.clientEngine.getClusterViewListenerService();
        if (service != null) {
            service.onMemberListChange();
        }
        node.clusterTopologyIntentTracker.onMembershipChange();
    }

    @Override
    public void onClusterVersionChange(Version newVersion) {
        if (!node.getVersion().asVersion().isEqualTo(newVersion)) {
            systemLogger.info("Cluster version set to " + newVersion);
        }
        ServiceManager serviceManager = node.getNodeEngine().getServiceManager();
        serviceManager.getServices(ClusterVersionListener.class)
                .forEach(listener -> listener.onClusterVersionChange(newVersion));
        // also trigger cluster version change on explicitly registered listeners
        clusterVersionListeners.forEach(listener -> listener.onClusterVersionChange(newVersion));
        ClusterViewListenerService service = node.clientEngine.getClusterViewListenerService();
        if (service != null) {
            service.onClusterVersionChange();
        }
    }

    @Override
    public boolean isNodeVersionCompatibleWith(Version clusterVersion) {
        Preconditions.checkNotNull(clusterVersion);
        return getSupportedVersions().contains(clusterVersion);
    }

    @Override
    public boolean registerListener(Object listener) {
        if (listener instanceof HazelcastInstanceAware aware) {
            aware.setHazelcastInstance(node.hazelcastInstance);
        }
        if (listener instanceof ClusterVersionListener clusterVersionListener) {
            clusterVersionListeners.add(clusterVersionListener);
            // on registration, invoke once the listening method so version is properly initialized on the listener
            clusterVersionListener.onClusterVersionChange(getClusterOrNodeVersion());
            return true;
        }
        return false;
    }

    @Override
    public HotRestartService getHotRestartService() {
        return new NoOpHotRestartService();
    }

    @Override
    public InternalHotRestartService getInternalHotRestartService() {
        return new NoopInternalHotRestartService();
    }

    // obtain cluster version, if already initialized (not null)
    // otherwise, if overridden with ClusterProperty#INIT_CLUSTER_VERSION, use this one
    // otherwise, if not overridden, use current node's codebase version
    private Version getClusterOrNodeVersion() {
        if (node.getClusterService() != null && !node.getClusterService().getClusterVersion().isUnknown()) {
            return node.getClusterService().getClusterVersion();
        } else {
            String overriddenClusterVersion = node.getProperties().getString(ClusterProperty.INIT_CLUSTER_VERSION);
            return (overriddenClusterVersion != null) ? MemberVersion.of(overriddenClusterVersion).asVersion()
                    : node.getVersion().asVersion();
        }
    }

    @Override
    public TimedMemberStateFactory createTimedMemberStateFactory(HazelcastInstanceImpl instance) {
        return new TimedMemberStateFactory(instance);
    }

    @Override
    public void registerPlugins(Diagnostics diagnostics) {
        final NodeEngineImpl nodeEngine = node.nodeEngine;
        HazelcastProperties properties = nodeEngine.getProperties();

        // static loggers at beginning of file
        diagnostics.register(new BuildInfoPlugin(nodeEngine.getLogger(BuildInfoPlugin.class)));
        diagnostics.register(new SystemPropertiesPlugin(nodeEngine.getLogger(SystemPropertiesPlugin.class)));
        diagnostics.register(new ConfigPropertiesPlugin(nodeEngine));

        // periodic loggers
        diagnostics.register(new OverloadedConnectionsPlugin(nodeEngine));
        diagnostics.register(new EventQueuePlugin(nodeEngine.getLogger(EventQueuePlugin.class),
                ((EventServiceImpl) nodeEngine.getEventService()).getEventExecutor(), properties));
        diagnostics.register(new PendingInvocationsPlugin(nodeEngine.getLogger(PendingInvocationsPlugin.class),
                nodeEngine.getOperationService().getInvocationRegistry(), properties));
        diagnostics.register(new MetricsPlugin(nodeEngine.getLogger(MetricsPlugin.class),
                nodeEngine.getMetricsRegistry(), properties));
        diagnostics.register(new SlowOperationPlugin(nodeEngine.getLogger(SlowOperationPlugin.class),
                nodeEngine.getOperationService(), properties));
        diagnostics.register(new InvocationSamplePlugin(nodeEngine.getLogger(InvocationSamplePlugin.class),
                nodeEngine.getOperationService().getInvocationRegistry(), properties));
        diagnostics.register(new InvocationProfilerPlugin(nodeEngine.getLogger(InvocationProfilerPlugin.class),
                nodeEngine.getOperationService().getInvocationRegistry(), properties));
        diagnostics.register(new OperationProfilerPlugin(nodeEngine.getLogger(OperationProfilerPlugin.class),
                nodeEngine.getOperationService().getOpLatencyDistributions(), properties));
        diagnostics.register(new MemberHazelcastInstanceInfoPlugin(nodeEngine));
        diagnostics.register(new SystemLogPlugin(nodeEngine.getLogger(SystemLogPlugin.class), properties, node.getServer(),
                nodeEngine.getHazelcastInstance(), node.getNodeExtension()));
        diagnostics.register(new StoreLatencyPlugin(nodeEngine.getLogger(StoreLatencyPlugin.class), properties));
        diagnostics.register(new MemberHeartbeatPlugin(nodeEngine.getLogger(MemberHeartbeatPlugin.class),
                nodeEngine.getClusterService(), properties));
        diagnostics.register(new NetworkingImbalancePlugin(nodeEngine.getLogger(NetworkingImbalancePlugin.class),
                properties, node.getServer()));
        diagnostics.register(new OperationHeartbeatPlugin(nodeEngine.getLogger(OperationHeartbeatPlugin.class),
                nodeEngine.getOperationService().getInvocationMonitor(), properties));
        diagnostics.register(new OperationThreadSamplerPlugin(nodeEngine));
    }

    @Override
    public ManagementService createJMXManagementService(HazelcastInstanceImpl instance) {
        return new ManagementService(instance);
    }

    @Override
    public TextCommandService createTextCommandService() {
        return new TextCommandServiceImpl(node);
    }

    @Override
    public CPPersistenceService getCPPersistenceService() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CPSubsystem createCPSubsystem(NodeEngine nodeEngine) {
        return new CPSubsystemStubImpl(false);
    }

    public void setLicenseKey(String licenseKey) {
        // NOP
    }

    @Override
    public AuditlogService getAuditlogService() {
        return NoOpAuditlogService.INSTANCE;
    }

    @Override
    public JetService getJet() {
        checkJetIsEnabled(node.nodeEngine);
        return jetServiceBackend.getJet();
    }

    @Override
    @Nullable
    public JetServiceBackend getJetServiceBackend() {
        return jetServiceBackend;
    }

    @Override
    public SSLEngineFactory createSslEngineFactory(SSLConfig sslConfig) {
        throw new IllegalStateException("SSL/TLS requires Hazelcast Enterprise Edition");
    }

    @Override
    public void onThreadStart(Thread thread) {
        // Setup NodeEngine context for User Code Deployment Namespacing in operations
        NodeEngineThreadLocalContext.declareNodeEngineReference(node.getNodeEngine());
    }

    @Override
    public void onThreadStop(Thread thread) {
        // Destroy NodeEngine context from User Code Deployment Namespacing
        NodeEngineThreadLocalContext.destroyNodeEngineReference();
    }

    @Override
    public UserCodeNamespaceService getNamespaceService() {
        return new NoOpUserCodeNamespaceService(node.getConfigClassLoader());
    }

    @Override
    public TpcServerBootstrap createTpcServerBootstrap() {
        return new TpcServerBootstrapImpl(node);
    }

    @Override
    public ClientEngine createClientEngine() {
        return new ClientEngineImpl(node);
    }

    @Override
    public HealthMonitor createHealthMonitor() {
        return new HealthMonitor(node);
    }
}
