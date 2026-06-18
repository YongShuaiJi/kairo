package com.example.runtimemock.object;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PropertyPathAccessor {

    private static final Set<String> FORBIDDEN_SEGMENTS = Set.of(
            "class",
            "classloader",
            "metaclass",
            "module",
            "protectiondomain",
            "declaredclasses",
            "declaredconstructors",
            "declaredfields",
            "declaredmethods"
    );

    public Object get(Object target, String path) {
        requirePath(path);
        Object current = target;
        for (String segment : path.split("\\.")) {
            if (current == null) {
                return null;
            }
            current = readSingle(current, segment);
        }
        return current;
    }

    public void set(Object target, String path, Object value) {
        requirePath(path);
        int lastDot = path.lastIndexOf('.');
        if (lastDot < 0) {
            writeSingle(target, path, value);
            return;
        }
        Object parent = get(target, path.substring(0, lastDot));
        if (parent == null) {
            throw new IllegalArgumentException("Cannot set property on null parent: " + path);
        }
        writeSingle(parent, path.substring(lastDot + 1), value);
    }

    private static void requirePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("property path must not be blank");
        }
        for (String segment : path.split("\\.")) {
            if (segment.isBlank() || !segment.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Invalid property path segment: " + segment);
            }
            if (FORBIDDEN_SEGMENTS.contains(segment.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Forbidden property path segment: " + segment);
            }
        }
    }

    private Object readSingle(Object target, String property) {
        if (target instanceof Map<?, ?> map) {
            return map.get(property);
        }

        Class<?> type = target.getClass();
        requireSafeTarget(type);
        String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        Method getter = findNoArgMethod(type, "get" + suffix);
        if (getter == null) {
            getter = findNoArgMethod(type, "is" + suffix);
        }
        if (getter != null) {
            try {
                getter.setAccessible(true);
                return getter.invoke(target);
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot read property " + property + " from " + type.getName(), e);
            }
        }

        Field field = findField(type, property);
        if (field != null) {
            try {
                field.setAccessible(true);
                return field.get(target);
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot read field " + property + " from " + type.getName(), e);
            }
        }
        throw new IllegalArgumentException("No readable property " + property + " on " + type.getName());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void writeSingle(Object target, String property, Object value) {
        if (target instanceof Map map) {
            map.put(property, value);
            return;
        }

        Class<?> type = target.getClass();
        requireSafeTarget(type);
        String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        Method setter = findOneArgMethod(type, "set" + suffix);
        if (setter != null) {
            try {
                setter.setAccessible(true);
                Object converted = TypeConverter.convert(value, setter.getParameterTypes()[0]);
                setter.invoke(target, converted);
                return;
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot write property " + property + " on " + type.getName(), e);
            }
        }

        Field field = findField(type, property);
        if (field != null) {
            try {
                field.setAccessible(true);
                field.set(target, TypeConverter.convert(value, field.getType()));
                return;
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot write field " + property + " on " + type.getName(), e);
            }
        }
        throw new IllegalArgumentException("No writable property " + property + " on " + type.getName());
    }

    private Method findNoArgMethod(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private Method findOneArgMethod(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 1) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static void requireSafeTarget(Class<?> type) {
        String name = type.getName();
        if (Class.class.isAssignableFrom(type)
                || ClassLoader.class.isAssignableFrom(type)
                || name.startsWith("java.lang.reflect.")
                || name.startsWith("java.lang.instrument.")
                || name.startsWith("net.bytebuddy.")
                || name.startsWith("groovy.lang.")) {
            throw new IllegalArgumentException("Property access is forbidden for type " + name);
        }
    }
}
