package com.example.kairo.agent.core;

import com.example.kairo.bridge.InvocationEnvelope;
import com.example.kairo.core.MethodDescriptor;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Constructor;

/**
 * Advice woven into constructors enhanced at V1.3 constructor locations.
 *
 * <p>Byte Buddy inserts {@link Advice.OnMethodEnter} after the implicit
 * {@code super()} call, so the enter advice fires at {@code CONSTRUCTOR_AFTER_SUPER}
 * with a fully initialized {@code this}. Constructors cannot be short-circuited
 * or have their result substituted (the object is already allocated), so the
 * enter is observe-only and the body always runs. Exception handling is woven
 * separately by {@link ConstructorThrowVisitorWrapper}: a JVM constructor may
 * not have an exception handler whose protected range includes the uninitialised
 * {@code this} before its first {@code super()} or {@code this()} invocation.
 */
public final class ConstructorAdvice {

    private ConstructorAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
            @Advice.Origin Class<?> declaringClass,
            @Advice.Origin Constructor<?> constructor,
            @Advice.This(optional = true, typing = Assigner.Typing.DYNAMIC) Object target,
            @Advice.AllArguments Object[] arguments,
            @Advice.Local("kairoToken") Object token
    ) {
        InvocationEnvelope envelope = InvocationEnvelope.builder()
                .location("CONSTRUCTOR_AFTER_SUPER")
                .declaringClass(declaringClass)
                .memberName("<init>")
                .descriptor(MethodDescriptor.of(constructor))
                .constructor(true)
                .target(target)
                .arguments(arguments == null ? new Object[0] : arguments)
                .build();
        token = ConstructorBridge.enter(envelope);
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
            @Advice.Local("kairoToken") Object token,
            @Advice.This(optional = true, typing = Assigner.Typing.DYNAMIC) Object target
    ) {
        ConstructorBridge.exitReturn(token, target);
    }
}
