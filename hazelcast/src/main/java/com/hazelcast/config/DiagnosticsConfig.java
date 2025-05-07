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
package com.hazelcast.config;

import com.hazelcast.internal.config.ConfigDataSerializerHook;
import com.hazelcast.internal.diagnostics.Diagnostics;
import com.hazelcast.internal.serialization.impl.SerializationUtil;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.hazelcast.internal.util.Preconditions.checkHasText;
import static com.hazelcast.internal.util.Preconditions.checkNotNull;
import static com.hazelcast.internal.util.Preconditions.checkPositive;

/**
 * Configuration for diagnostics service.
 *
 * @since 6.0
 */
public class DiagnosticsConfig implements IdentifiedDataSerializable {

    /**
     * Default value of maximum rolled diagnostics output file size in MB.
     */
    public static final int DEFAULT_MAX_ROLLED_FILE_SIZE = 50;
    /**
     * Default value of maximum rolled diagnostics output file count.
     */
    public static final int DEFAULT_MAX_ROLLED_FILE_COUNT = 10;
    /**
     * Default value either include epoch time on diagnostics output.
     */
    public static final boolean DEFAULT_INCLUDE_EPOCH_TIME = true;
    /**
     * Default value of diagnostics output type.
     */
    public static final DiagnosticsOutputType DEFAULT_OUTPUT_TYPE = DiagnosticsOutputType.FILE;
    /**
     * Default value of output directory.
     */
    public static final String DEFAULT_DIRECTORY = System.getProperty("user.dir");
    /**
     * Default value of diagnostics whether enabled or not.
     */
    public static final boolean DEFAULT_ENABLED = false;

    private boolean enabled;
    private int maxRolledFileSizeInMB = DEFAULT_MAX_ROLLED_FILE_SIZE;
    private int maxRolledFileCount = DEFAULT_MAX_ROLLED_FILE_COUNT;
    private boolean includeEpochTime = DEFAULT_INCLUDE_EPOCH_TIME;
    private String logDirectory = DEFAULT_DIRECTORY;
    private String fileNamePrefix;
    private DiagnosticsOutputType outputType = DEFAULT_OUTPUT_TYPE;
    private Map<String, String> pluginProperties = new HashMap<>();

    public DiagnosticsConfig() {
    }

    public DiagnosticsConfig(boolean enabled) {
        this.enabled = enabled;
    }

    public DiagnosticsConfig(DiagnosticsConfig diagnosticsConfig) {
        this.enabled = diagnosticsConfig.isEnabled();
        this.maxRolledFileSizeInMB = diagnosticsConfig.getMaxRolledFileSizeInMB();
        this.maxRolledFileCount = diagnosticsConfig.getMaxRolledFileCount();
        this.includeEpochTime = diagnosticsConfig.isIncludeEpochTime();
        this.logDirectory = diagnosticsConfig.getLogDirectory();
        this.fileNamePrefix = diagnosticsConfig.getFileNamePrefix();
        this.outputType = diagnosticsConfig.getOutputType();
        this.pluginProperties = diagnosticsConfig.pluginProperties;
    }

    /**
     * Returns true if {@link Diagnostics} is enabled.
     *
     * @return true if enabled, false otherwise
     * @since 6.0
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables {@link Diagnostics} to see internal performance metrics and cluster
     * related information.
     * <p>
     * The performance monitor logs all metrics into the log file.
     * <p>
     * For more detailed information, please check the METRICS_LEVEL.
     * <p>
     * The default is {@code false}.
     *
     * @since 6.0
     */
    public DiagnosticsConfig setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /**
     * Gets the maximum size in MB for a single file.
     * The DiagnosticsLogFile uses a rolling file approach to prevent
     * using too much disk space.
     * <p>
     * This property sets the maximum size in MB for a single file.
     * <p>
     * Every HazelcastInstance will get its own history of log files.
     * <p>
     * The default is 50.
     *
     * @since 6.0
     */
    public int getMaxRolledFileSizeInMB() {
        return maxRolledFileSizeInMB;
    }

    /**
     * Sets the maximum size in MB for a single file.
     * The DiagnosticsLogFile uses a rolling file approach to prevent
     * using too much disk space.
     * <p>
     * This property sets the maximum size in MB for a single file.
     * <p>
     * Every HazelcastInstance will get its own history of log files.
     * <p>
     * The default is 50.
     */
    public DiagnosticsConfig setMaxRolledFileSizeInMB(int maxRolledFileSizeInMB) {
        this.maxRolledFileSizeInMB = checkPositive("maxRolledFileSizeInMB must be positive",
                maxRolledFileSizeInMB);
        return this;
    }

    /**
     * Gets the maximum number of rolling files to keep on disk.
     * The DiagnosticsLogFile uses a rolling file approach to prevent
     * using too much disk space.
     * <p>
     * This property sets the maximum number of rolling files to keep on disk.
     * <p>
     * The default is 10.
     *
     * @since 6.0
     */
    public int getMaxRolledFileCount() {
        return maxRolledFileCount;
    }

    /**
     * Sets the maximum number of rolling files to keep on disk.
     * The DiagnosticsLogFile uses a rolling file approach to prevent
     * using too much disk space.
     * <p>
     * This property sets the maximum number of rolling files to keep on disk.
     * <p>
     * The default is 10.
     *
     * @since 6.0
     */
    public DiagnosticsConfig setMaxRolledFileCount(int maxRolledFileCount) {
        this.maxRolledFileCount = checkPositive("maxRolledFileCount must be positive",
                maxRolledFileCount);
        return this;
    }


    /**
     * Returns true if the epoch time should be included in the 'top' section.
     */
    public boolean isIncludeEpochTime() {
        return includeEpochTime;
    }

    /**
     * Configures if the epoch time should be included in the 'top' section.
     * This makes it easy to determine the time in epoch format and prevents
     * needing to parse the date-format section. The default is {@code true}.
     *
     * @since 6.0
     */
    public DiagnosticsConfig setIncludeEpochTime(boolean includeEpochTime) {
        this.includeEpochTime = includeEpochTime;
        return this;
    }

    /**
     * Gets the output directory of the performance log files.
     * <p>
     * Defaults to the value of the 'user.dir' system property.
     *
     * @since 6.0
     */
    public String getLogDirectory() {
        return logDirectory;
    }

    /**
     * Configures the output directory of the performance log files.
     * <p>
     * Defaults to the 'user.dir'.
     *
     * @since 6.0
     */
    public DiagnosticsConfig setLogDirectory(@Nonnull String logDirectory) {
        this.logDirectory = checkHasText(logDirectory, "logDirectory must not be null");
        return this;
    }

    /**
     * Gets the prefix for the diagnostics file.
     * <p>
     * So instead of having e.g. 'diagnostics-...log' you get 'foobar-diagnostics-...log'.
     *
     * @since 6.0
     */
    public String getFileNamePrefix() {
        return fileNamePrefix;
    }

    /**
     * Configures the prefix for the diagnostics file.
     * <p>
     * So instead of having e.g. 'diagnostics-...log' you get 'foobar-diagnostics-...log'.
     *
     * @since 6.0
     */
    public DiagnosticsConfig setFileNamePrefix(String fileNamePrefix) {
        this.fileNamePrefix = fileNamePrefix;
        return this;
    }

    /**
     * Gets the output for the diagnostics. The default value is
     * {@link DiagnosticsOutputType#FILE} which is a set of files managed by the
     * Hazelcast process.
     *
     * @since 6.0
     */
    public DiagnosticsOutputType getOutputType() {
        return outputType;
    }

    /**
     * Configures the output for the diagnostics. The default value is
     * {@link DiagnosticsOutputType#FILE} which is a set of files managed by the
     * Hazelcast process.
     *
     * @since 6.0
     */
    public DiagnosticsConfig setOutputType(@Nonnull DiagnosticsOutputType outputType) {
        this.outputType = checkNotNull(outputType, "outputType must not be null");
        return this;
    }

    /**
     * Gets properties of the Diagnostic Configuration. The properties are used by
     * diagnostic plugins.
     * <p>Note that the keys and values are not verified. Make sure that the keys and values
     * are valid and compatible with the diagnostic plugins.</p>
     *
     * @return Plugin properties of the Diagnostic Configuration
     * @since 6.0
     */
    public Map<String, String> getPluginProperties() {
        return this.pluginProperties;
    }

    /**
     * Sets properties of the Diagnostic Configuration. The properties are used by diagnostic plugins.
     * <p>Note that the keys and values are not verified. Make sure that the keys and values
     * are valid and compatible with the diagnostic plugins.</p>
     *
     * @param name  property name
     * @param value property value
     * @since 6.0
     */
    public DiagnosticsConfig setProperty(String name, String value) {
        this.pluginProperties.put(checkNotNull(name), checkNotNull(value));
        return this;
    }

    @Override
    public int getFactoryId() {
        return ConfigDataSerializerHook.F_ID;
    }

    @Override
    public int getClassId() {
        return ConfigDataSerializerHook.DIAGNOSTICS_CONFIG;
    }

    @Override
    public void writeData(ObjectDataOutput out) throws IOException {
        out.writeBoolean(enabled);
        out.writeInt(maxRolledFileSizeInMB);
        out.writeInt(maxRolledFileCount);
        out.writeBoolean(includeEpochTime);
        out.writeString(logDirectory);
        out.writeString(fileNamePrefix);
        out.writeString(outputType.name());
        SerializationUtil.writeMapStringKey(pluginProperties, out);
    }

    @Override
    public void readData(ObjectDataInput in) throws IOException {
        enabled = in.readBoolean();
        maxRolledFileSizeInMB = in.readInt();
        maxRolledFileCount = in.readInt();
        includeEpochTime = in.readBoolean();
        logDirectory = in.readString();
        fileNamePrefix = in.readString();
        outputType = DiagnosticsOutputType.valueOf(in.readString());
        Map<String, String> properties = SerializationUtil.readMapStringKey(in);
        pluginProperties = properties;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DiagnosticsConfig)) {
            return false;
        }
        DiagnosticsConfig that = (DiagnosticsConfig) o;
        return enabled == that.enabled
                && maxRolledFileSizeInMB == that.maxRolledFileSizeInMB
                && maxRolledFileCount == that.maxRolledFileCount
                && includeEpochTime == that.includeEpochTime
                && Objects.equals(logDirectory, that.logDirectory)
                && Objects.equals(fileNamePrefix, that.fileNamePrefix)
                && Objects.equals(outputType, that.outputType)
                && Objects.equals(pluginProperties, that.pluginProperties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, maxRolledFileSizeInMB, maxRolledFileCount,
                includeEpochTime, logDirectory, fileNamePrefix, outputType, pluginProperties);
    }

    @Override
    public String toString() {

        String properties = "";

        if (this.pluginProperties != null) {
            properties = this.pluginProperties
                    .keySet()
                    .stream()
                    .map(key -> key + "=" + this.pluginProperties.get(key))
                    .collect(Collectors.joining(", ", "{", "}"));
        }

        return "DiagnosticsConfig{"
                + "enabled=" + enabled
                + ", maxRolledFileSizeInMB=" + maxRolledFileSizeInMB
                + ", maxRolledFileCount=" + maxRolledFileCount
                + ", includeEpochTime=" + includeEpochTime
                + ", logDirectory='" + logDirectory + '\''
                + ", fileNamePrefix='" + fileNamePrefix + '\''
                + ", outputType=" + outputType
                + ", properties='" + properties
                + "'}";
    }
}
