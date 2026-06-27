package com.example.runtimemock.agent.core;

import example.demo.ExampleTarget;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoadedClassRepositoryTest {

    @Test
    void searchesLoadedClassesByMethodNameCaseInsensitively() {
        LoadedClassRepository repository = new LoadedClassRepository(instrumentationFor(ExampleTarget.class));

        var results = repository.search("CALCULATESCORE", 10);

        assertEquals(1, results.size());
        assertEquals(ExampleTarget.class.getName(), results.get(0).className());
    }

    @Test
    void searchesLoadedClassesByCompleteTargetSignature() {
        LoadedClassRepository repository = new LoadedClassRepository(instrumentationFor(ExampleTarget.class));

        var results = repository.search(
                ExampleTarget.class.getName() + "#calculateScore(I)I",
                10
        );

        assertEquals(1, results.size());
        assertEquals(ExampleTarget.class.getName(), results.get(0).className());
    }

    private Instrumentation instrumentationFor(Class<?>... loadedClasses) {
        return (Instrumentation) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAllLoadedClasses" -> loadedClasses;
                    case "isModifiableClass" -> true;
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }
}
