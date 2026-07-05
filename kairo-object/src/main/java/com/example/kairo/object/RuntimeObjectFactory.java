package com.example.kairo.object;

public interface RuntimeObjectFactory {

    Object fromJson(String json, Class<?> targetType, ClassLoader targetClassLoader);

    Object newInstance(Class<?> targetType);

    Throwable newThrowable(String className, String message, ClassLoader targetClassLoader);

    Object getProperty(Object target, String path);

    void setProperty(Object target, String path, Object value);
}
