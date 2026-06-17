package com.example.runtimemock.agent.core;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class ByteBuddyTransformerManager implements AutoCloseable {

    private final Instrumentation instrumentation;
    private final InstrumentationRegistry registry;
    private final AtomicLong retransformCount = new AtomicLong();
    private ResettableClassFileTransformer transformer;

    public ByteBuddyTransformerManager(Instrumentation instrumentation, InstrumentationRegistry registry) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public synchronized void install() {
        if (transformer != null) {
            return;
        }
        transformer = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .ignore(this::ignore)
                .type(this::matchesRegisteredType)
                .transform(new RuntimeMockTransformer(registry))
                .installOn(instrumentation);
    }

    public void retransform(Class<?>... classes) {
        Class<?>[] modifiable = Arrays.stream(classes)
                .filter(instrumentation::isModifiableClass)
                .toArray(Class<?>[]::new);
        if (modifiable.length == 0) {
            return;
        }
        try {
            instrumentation.retransformClasses(modifiable);
            retransformCount.incrementAndGet();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot retransform classes", e);
        }
    }

    public long retransformCount() {
        return retransformCount.get();
    }

    @Override
    public synchronized void close() {
        if (transformer == null) {
            return;
        }
        transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
        transformer = null;
    }

    private boolean matchesRegisteredType(TypeDescription typeDescription, ClassLoader classLoader,
                                          JavaModule module, Class<?> classBeingRedefined,
                                          ProtectionDomain protectionDomain) {
        return registry.containsType(typeDescription.getName(), classLoader);
    }

    private boolean ignore(TypeDescription typeDescription, ClassLoader classLoader,
                           JavaModule module, Class<?> classBeingRedefined,
                           ProtectionDomain protectionDomain) {
        String name = typeDescription.getName();
        return name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jdk.")
                || name.startsWith("sun.")
                || name.startsWith("com.sun.")
                || name.startsWith("net.bytebuddy.")
                || name.startsWith("groovy.")
                || name.startsWith("org.codehaus.groovy.")
                || name.startsWith("com.example.runtimemock.");
    }
}
