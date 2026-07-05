package com.example.kairo.api;

public interface InvocationContext {

    InvokePhase phase();

    Object[] arguments();

    Object target();

    Object result();

    Throwable throwable();

    MethodMetadata method();

    MockApi mockApi();

    ScriptLog log();
}
