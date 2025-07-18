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

package com.hazelcast.jet.impl.util;

import com.hazelcast.core.HazelcastException;
import com.hazelcast.internal.nio.ClassLoaderUtil;
import com.hazelcast.jet.JetException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hazelcast.internal.util.ExceptionUtil.sneakyThrow;
import static java.lang.Character.toUpperCase;
import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.concat;

public final class ReflectionUtils {

    private ReflectionUtils() {
    }

    /**
     * Load a class using the current thread's context class loader as a
     * classLoaderHint. Exceptions are sneakily thrown.
     */
    public static <T> Class<T> loadClass(String name) {
        return loadClass(Thread.currentThread().getContextClassLoader(), name);
    }

    /**
     * See {@link ClassLoaderUtil#loadClass(ClassLoader, String)}. Exceptions
     * are sneakily thrown.
     */
    public static <T> Class<T> loadClass(ClassLoader classLoaderHint, String name) {
        try {
            return ClassLoaderUtil.loadClass(classLoaderHint, name);
        } catch (ClassNotFoundException e) {
            throw sneakyThrow(e);
        }
    }
    /**
     * See {@link ClassLoaderUtil#newInstance(ClassLoader, String)}. Exceptions
     * are sneakily thrown.
     */
    public static <T> T newInstance(ClassLoader classLoader, String  typeName) {
        return newInstance(classLoader, typeName, typeName);
    }

    /**
     * See {@link ClassLoaderUtil#newInstance(ClassLoader, String)}. Exceptions
     * are sneakily thrown.
     */
    public static <T> T newInstance(ClassLoader classLoader, String  typeName, String type) {
        try {
            return ClassLoaderUtil.newInstance(classLoader, typeName);
        } catch (ClassNotFoundException e) {
            throw new JetException(String.format("%s class %s not found", type, typeName));
        } catch (InstantiationException e) {
            throw new JetException(String.format("%s class %s can't be instantiated", type, typeName));
        } catch (IllegalArgumentException | NoSuchMethodException e) {
            throw new JetException(String.format("%s class %s has no default constructor", type, typeName));
        } catch (IllegalAccessException e) {
            throw new JetException(String.format("Default constructor of %s class %s is not accessible", type, typeName));
        } catch (InvocationTargetException e) {
            throw new JetException(
                    String.format("%s class %s failed on construction: %s", type, typeName, e.getMessage()), e);
        } catch (Exception e) {
            throw new JetException(e);
        }
    }

    /**
     * Reads a value of a static field. In case of any exceptions it returns
     * null.
     */
    public static <T> T readStaticFieldOrNull(String className, String fieldName) {
        try {
            Class<?> clazz = Class.forName(className);
            return readStaticFieldOrNull(clazz, fieldName);
        } catch (ClassNotFoundException | SecurityException e) {
            return null;
        }
    }

    /**
     * Reads a value of a static field. In case of any exceptions it returns
     * null.
     */
    public static <T> T readStaticFieldOrNull(Class<?> clazz, String fieldName) {
        try {
            return readStaticFieldInternal(clazz, fieldName);
        } catch (NoSuchFieldException | IllegalAccessException | SecurityException e) {
            return null;
        }
    }

    /**
     * Reads a value of a static field. In case of any exceptions it returns
     * null.
     */
    @Nonnull
    public static <T> T readStaticField(Class<?> clazz, String fieldName) {
        try {
            return requireNonNull(readStaticFieldInternal(clazz, fieldName), fieldName + " must be non-null");
        } catch (NoSuchFieldException | IllegalAccessException | SecurityException e) {
            throw new HazelcastException("Error when accessing field " + fieldName, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T readStaticFieldInternal(Class<?> clazz, String fieldName) throws NoSuchFieldException,
            IllegalAccessException {
        Field field = clazz.getDeclaredField(fieldName);
        if (!field.canAccess(null)) {
            field.setAccessible(true);
        }
        return (T) field.get(null);
    }

    /**
     * Return a set-method for a class and a property. The setter must start
     * with "set", must be public, non-static, must return void or the
     * containing class type (a builder-style setter) and take one argument of
     * {@code propertyType}.
     *
     * @param clazz        The containing class
     * @param propertyName Name of the property
     * @param propertyType The propertyType of the property
     * @return The found setter or null if one matching the criteria doesn't exist
     */
    @Nullable
    public static Method findPropertySetter(
            @Nonnull Class<?> clazz,
            @Nonnull String propertyName,
            @Nonnull Class<?> propertyType
    ) {
        String setterName = "set" + toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);

        Method method;
        try {
            method = clazz.getMethod(setterName, propertyType);
        } catch (NoSuchMethodException e) {
            return null;
        }

        if (!Modifier.isPublic(method.getModifiers())) {
            return null;
        }

        if (Modifier.isStatic(method.getModifiers())) {
            return null;
        }

        Class<?> returnType = method.getReturnType();
        if (returnType != void.class && returnType != Void.class && returnType != clazz) {
            return null;
        }

        return method;
    }

    @Nullable
    public static Method findPropertySetter(@Nonnull Class<?> clazz, @Nonnull String propertyName) {
        final Method setter = findPropertyAccessor(clazz, propertyName, "set");
        if (setter == null) {
            return null;
        }

        Class<?> returnType = setter.getReturnType();
        if (returnType != void.class && returnType != Void.class && returnType != clazz) {
            return null;
        }
        return setter;
    }

    public static Method findPropertyGetter(@Nonnull Class<?> clazz, @Nonnull String propertyName) {
        Method getter = findPropertyAccessor(clazz, propertyName, "get", "is");
        if (getter == null) {
            return null;
        }

        Class<?> returnType = getter.getReturnType();
        if (returnType == void.class || returnType == Void.class) {
            return null;
        }

        return getter;
    }

    private static Method findPropertyAccessor(
            @Nonnull Class<?> clazz,
            @Nonnull String propertyName,
            @Nonnull String... prefixes
    ) {
        final Set<String> accessorNames = stream(prefixes)
                .map(prefix -> prefix + toUpperCase(propertyName.charAt(0)) + propertyName.substring(1))
                .collect(toSet());
        Method method = stream(clazz.getMethods())
                .filter(m -> accessorNames.contains(m.getName()))
                .findAny()
                .orElse(null);

        if (method == null) {
            return null;
        }

        if (Modifier.isStatic(method.getModifiers())) {
            return null;
        }

        return method;
    }

    /**
     * Return a {@link Field} object for the given {@code fieldName}. The field
     * must be public and non-static.
     *
     * @param clazz     The containing class
     * @param fieldName The field
     * @return The field object or null, if not found or doesn't match the criteria.
     */
    @Nullable
    public static Field findPropertyField(Class<?> clazz, String fieldName) {
        Field field;
        try {
            field = clazz.getField(fieldName);
        } catch (NoSuchFieldException e) {
            return null;
        }

        if (!Modifier.isPublic(field.getModifiers()) || Modifier.isStatic(field.getModifiers())) {
            return null;
        }

        return field;
    }

    @Nonnull
    @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE", justification =
            "False positive on try-with-resources as of JDK11")
    public static Collection<Class<?>> nestedClassesOf(Class<?>... classes) {
        ClassGraph classGraph = new ClassGraph()
                .enableClassInfo()
                .ignoreClassVisibility();
        stream(classes).map(Class::getClassLoader).distinct().forEach(classGraph::addClassLoader);
        stream(classes).map(ReflectionUtils::toPackageName).distinct().forEach(classGraph::acceptPackages);
        try (ScanResult scanResult = classGraph.scan()) {
            Set<String> classNames = stream(classes).map(Class::getName).collect(toSet());
            return concat(
                    stream(classes),
                    scanResult.getAllClasses()
                            .stream()
                            .filter(classInfo -> classNames.contains(classInfo.getName()))
                            .flatMap(classInfo -> classInfo.getInnerClasses().stream())
                            .map(ClassInfo::loadClass)
            ).collect(toList());
        }
    }

    private static String toPackageName(Class<?> clazz) {
        return Optional.ofNullable(clazz.getPackage()).map(Package::getName).orElse("");
    }

    @Nonnull
    @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE", justification =
            "False positive on try-with-resources as of JDK11")
    public static Resources resourcesOf(String... packages) {
        String[] paths = stream(packages).map(ReflectionUtils::toPath).toArray(String[]::new);
        ClassGraph classGraph = new ClassGraph()
                .acceptPackages(packages)
                .acceptPaths(paths)
                .ignoreClassVisibility();
        try (ScanResult scanResult = classGraph.scan()) {
            Collection<ClassResource> classes =
                    scanResult.getAllClasses().stream().map(ClassResource::new).collect(toList());
            try (var res = scanResult.getAllResources().nonClassFilesOnly()) {
                Collection<URL> nonClasses = res.getURLs();
                return new Resources(classes, nonClasses);
            }
        }
    }

    private static String toPath(String name) {
        return name.replace('.', '/');
    }

    public static String toClassResourceId(String name) {
        return toPath(name) + ".class";
    }

    public static String toClassResourceId(Class<?> clazz) {
        return toClassResourceId(clazz.getName());
    }

    @Nullable
    public static byte[] getClassContent(String name, ClassLoader classLoader) throws IOException {
        try (InputStream is = classLoader.getResourceAsStream(toClassResourceId(name))) {
            if (is == null) {
                return null;
            } else {
                return is.readAllBytes();
            }
        }
    }

    public static Object getFieldValue(String fieldName, Object obj) {
        final Method getter = findPropertyGetter(obj.getClass(), fieldName);
        if (getter != null) {
            try {
                return getter.invoke(obj);
            } catch (IllegalAccessException | InvocationTargetException ignored) { }
        }

        final Field field = findPropertyField(obj.getClass(), fieldName);
        if (field == null) {
            return null;
        }

        try {
            return field.get(obj);
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    /**
     * Reads only the necessary amount of bytes for the provided {@link Class} to find and return
     * the internal binary name for this class, as determined by the {@code CONSTANT_Class} found
     * in the constants pool at the index determined by the {@code this_class} field.
     *
     * @see <a href="https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html">Java Class file format</a>
     *
     * @param classBytes the bytes of a Java {@link Class} to extract binary name from
     * @return           the binary name of the class
     */
    @SuppressWarnings("checkstyle:magicnumber")
    public static String getInternalBinaryName(byte[] classBytes) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(classBytes);
            buffer.order(ByteOrder.BIG_ENDIAN);

            // Skip magic number and major/minor versions
            buffer.position(8);

            int constantPoolCount = buffer.getShort() & 0xFFFF;
            Object[] constantPool = new Object[constantPoolCount];

            // Iterate constant pool, collecting UTF8 strings (could be our class name) and looking for CONSTANT_Class tags
            //   to identify our desired UTF8 string representing the class name. Skips appropriate bytes for all other tags.
            // While it is generally convention for the index referenced by a CONSTANT_Class value to already be populated in
            //   the constant pool (forward references), it is not forbidden by JVM Spec to use backward references.
            // We need to skip the payload of all irrelevant tags, by the amount defined in the JVM spec (see javadoc)
            for (int i = 1; i < constantPoolCount; i++) {
                int tag = buffer.get() & 0xFF;
                switch (tag) {
                    case 1: // CONSTANT_Utf8
                        int length = buffer.getShort() & 0xFFFF;
                        byte[] bytes = new byte[length];
                        buffer.get(bytes);
                        constantPool[i] = new String(bytes, StandardCharsets.UTF_8);
                        break;
                    case 7: // CONSTANT_Class
                        constantPool[i] = buffer.getShort() & 0xFFFF; // Store index
                        break;
                    case 8: // CONSTANT_String
                    case 16: // CONSTANT_MethodType
                    case 19: // CONSTANT_Module
                    case 20: // CONSTANT_Package
                        skipBytes(buffer, 2);
                        break;
                    case 15: // CONSTANT_MethodHandle
                        skipBytes(buffer, 3);
                        break;
                    case 3: // CONSTANT_Integer
                    case 4: // CONSTANT_Float
                    case 9: // CONSTANT_Fieldref
                    case 10: // CONSTANT_Methodref
                    case 11: // CONSTANT_InterfaceMethodref
                    case 12: // CONSTANT_NameAndType
                    case 18: // CONSTANT_InvokeDynamic
                    case 17: // CONSTANT_Dynamic
                        skipBytes(buffer, 4);
                        break;
                    case 5: // CONSTANT_Long
                    case 6: // CONSTANT_Double
                        skipBytes(buffer, 8);
                        i++;
                        break;
                    default:
                        throw new IllegalArgumentException("Invalid constant pool tag: " + tag);
                }
            }

            // Skip access flag
            skipBytes(buffer, 2);

            // Read this_class index which points to the constantPool index which holds the value of
            //     the index to find the current classes' internal binary name
            int thisClassIndex = buffer.getShort() & 0xFFFF;
            return (String) constantPool[(int) constantPool[thisClassIndex]];
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to local package/class names from class bytes!", e);
        }
    }

    private static void skipBytes(ByteBuffer buffer, int toSkip) {
        buffer.position(buffer.position() + toSkip);
    }

    public static String getStackTrace(Thread thread) {
        return getStackTrace(thread, 3);
    }

    public static String getStackTrace(Thread thread, int skip) {
        return Arrays.stream(thread.getStackTrace()).skip(skip)
                .map(Object::toString)
                .collect(Collectors.joining(System.lineSeparator() + "\t"));
    }

    public static final class Resources {

        private final Collection<ClassResource> classes;
        private final Collection<URL> nonClasses;

        private Resources(Collection<ClassResource> classes, Collection<URL> nonClasses) {
            this.classes = classes;
            this.nonClasses = nonClasses;
        }

        public Stream<ClassResource> classes() {
            return classes.stream();
        }

        public Stream<URL> nonClasses() {
            return nonClasses.stream();
        }
    }

    public static final class ClassResource {

        private final String id;
        private final URL url;

        private ClassResource(ClassInfo classInfo) {
            this(classInfo.getName(), classInfo.getResource().getURL());
        }

        public ClassResource(String name, URL url) {
            this.id = toClassResourceId(name);
            this.url = url;
        }

        public String getId() {
            return id;
        }

        public URL getUrl() {
            return url;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ClassResource that = (ClassResource) o;
            return Objects.equals(id, that.id) &&
                    Objects.equals(url, that.url);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, url);
        }
    }
}
