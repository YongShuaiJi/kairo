package com.example.runtimemock.object;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Constructor;

public final class DefaultRuntimeObjectFactory implements RuntimeObjectFactory {

    private final ObjectMapper objectMapper;
    private final PropertyPathAccessor propertyPathAccessor;

    public DefaultRuntimeObjectFactory() {
        this(new ObjectMapper()
                .findAndRegisterModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
    }

    public DefaultRuntimeObjectFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.propertyPathAccessor = new PropertyPathAccessor();
    }

    @Override
    public Object fromJson(String json, Class<?> targetType, ClassLoader targetClassLoader) {
        try {
            return objectMapper.readValue(json, objectMapper.constructType(targetType));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot deserialize JSON to " + targetType.getName(), e);
        }
    }

    @Override
    public Object newInstance(Class<?> targetType) {
        if (targetType == String.class) {
            return "";
        }
        if (targetType.isPrimitive()) {
            return TypeConverter.defaultPrimitiveValue(targetType);
        }
        try {
            Constructor<?> constructor = targetType.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot create instance of " + targetType.getName(), e);
        }
    }

    @Override
    public Throwable newThrowable(String className, String message, ClassLoader targetClassLoader) {
        ClassLoader loader = targetClassLoader == null ? Thread.currentThread().getContextClassLoader() : targetClassLoader;
        try {
            Class<?> type = Class.forName(className, true, loader);
            if (!Throwable.class.isAssignableFrom(type)) {
                throw new IllegalArgumentException(className + " is not a Throwable");
            }
            @SuppressWarnings("unchecked")
            Class<? extends Throwable> throwableType = (Class<? extends Throwable>) type;
            try {
                Constructor<? extends Throwable> constructor = throwableType.getDeclaredConstructor(String.class);
                constructor.setAccessible(true);
                return constructor.newInstance(message);
            } catch (NoSuchMethodException ignored) {
                Constructor<? extends Throwable> constructor = throwableType.getDeclaredConstructor();
                constructor.setAccessible(true);
                Throwable throwable = constructor.newInstance();
                return message == null ? throwable : new RuntimeException(message, throwable);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot create throwable " + className, e);
        }
    }

    @Override
    public Object getProperty(Object target, String path) {
        return propertyPathAccessor.get(target, path);
    }

    @Override
    public void setProperty(Object target, String path, Object value) {
        propertyPathAccessor.set(target, path, value);
    }
}
