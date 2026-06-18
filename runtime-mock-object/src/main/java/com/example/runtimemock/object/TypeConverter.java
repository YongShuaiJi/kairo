package com.example.runtimemock.object;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashSet;

public final class TypeConverter {

    private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPERS = Map.of(
            boolean.class, Boolean.class,
            byte.class, Byte.class,
            short.class, Short.class,
            int.class, Integer.class,
            long.class, Long.class,
            float.class, Float.class,
            double.class, Double.class,
            char.class, Character.class
    );

    private TypeConverter() {
    }

    public static boolean isAssignable(Class<?> targetType, Object value) {
        if (value == null) {
            return !targetType.isPrimitive();
        }
        Class<?> normalizedTarget = wrap(targetType);
        if (normalizedTarget.isInstance(value)) {
            return true;
        }
        if (Number.class.isAssignableFrom(normalizedTarget) && value instanceof Number) {
            return true;
        }
        if (targetType.isEnum() && value instanceof String) {
            return true;
        }
        return normalizedTarget == Character.class && value instanceof String text && text.length() == 1;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object convert(Object value, Class<?> targetType) {
        if (value == null) {
            if (targetType.isPrimitive()) {
                throw new IllegalArgumentException("Cannot assign null to primitive " + targetType.getName());
            }
            return null;
        }
        Class<?> normalizedTarget = wrap(targetType);
        if (normalizedTarget.isInstance(value)) {
            return value;
        }
        if (targetType.isArray() && value instanceof Collection<?> collection) {
            Class<?> componentType = targetType.getComponentType();
            Object array = java.lang.reflect.Array.newInstance(componentType, collection.size());
            int index = 0;
            for (Object item : collection) {
                java.lang.reflect.Array.set(array, index++, convert(item, componentType));
            }
            return array;
        }
        if (Collection.class.isAssignableFrom(targetType)
                && (value instanceof Collection<?> || value.getClass().isArray())) {
            Collection<Object> converted = java.util.Set.class.isAssignableFrom(targetType)
                    ? new LinkedHashSet<>()
                    : new ArrayList<>();
            if (value instanceof Collection<?> collection) {
                converted.addAll(collection);
            } else {
                int length = java.lang.reflect.Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    converted.add(java.lang.reflect.Array.get(value, i));
                }
            }
            return converted;
        }
        if (normalizedTarget == String.class) {
            return String.valueOf(value);
        }
        if (normalizedTarget == BigDecimal.class) {
            return value instanceof Number number ? BigDecimal.valueOf(number.doubleValue()) : new BigDecimal(value.toString());
        }
        if (Number.class.isAssignableFrom(normalizedTarget)) {
            BigDecimal decimal = value instanceof Number number
                    ? BigDecimal.valueOf(number.doubleValue())
                    : new BigDecimal(value.toString());
            if (normalizedTarget == Byte.class) {
                return decimal.byteValue();
            }
            if (normalizedTarget == Short.class) {
                return decimal.shortValue();
            }
            if (normalizedTarget == Integer.class) {
                return decimal.intValue();
            }
            if (normalizedTarget == Long.class) {
                return decimal.longValue();
            }
            if (normalizedTarget == Float.class) {
                return decimal.floatValue();
            }
            if (normalizedTarget == Double.class) {
                return decimal.doubleValue();
            }
        }
        if (normalizedTarget == Boolean.class) {
            return value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
        }
        if (normalizedTarget == Character.class) {
            String text = value.toString();
            if (text.length() == 1) {
                return text.charAt(0);
            }
        }
        if (targetType.isEnum()) {
            return Enum.valueOf((Class<? extends Enum>) targetType, value.toString());
        }
        throw new IllegalArgumentException("Cannot convert " + value.getClass().getName() + " to " + targetType.getName());
    }

    public static Class<?> wrap(Class<?> type) {
        return type.isPrimitive() ? PRIMITIVE_WRAPPERS.get(type) : type;
    }

    public static Object defaultPrimitiveValue(Class<?> primitiveType) {
        if (primitiveType == boolean.class) {
            return false;
        }
        if (primitiveType == char.class) {
            return '\0';
        }
        if (primitiveType == byte.class) {
            return (byte) 0;
        }
        if (primitiveType == short.class) {
            return (short) 0;
        }
        if (primitiveType == int.class) {
            return 0;
        }
        if (primitiveType == long.class) {
            return 0L;
        }
        if (primitiveType == float.class) {
            return 0F;
        }
        if (primitiveType == double.class) {
            return 0D;
        }
        throw new IllegalArgumentException(primitiveType.getName() + " is not primitive");
    }
}
