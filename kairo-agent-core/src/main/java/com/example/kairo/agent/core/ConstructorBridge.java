package com.example.kairo.agent.core;

import com.example.kairo.bridge.BridgeAction;
import com.example.kairo.bridge.InvocationEnvelope;
import com.example.kairo.bridge.KairoBridge;
import com.example.kairo.bridge.OutcomeEnvelope;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Per-thread constructor invocation state shared by constructor Advice and the
 * post-initialisation exception handler. A stack is required for {@code this()}
 * chains and nested object construction.
 */
public final class ConstructorBridge {

    private static final ThreadLocal<Deque<Object>> TOKENS = ThreadLocal.withInitial(ArrayDeque::new);

    private ConstructorBridge() {
    }

    public static Object enter(InvocationEnvelope envelope) {
        Object token = KairoBridge.enterV2(envelope).getInvocationToken();
        TOKENS.get().push(token == null ? NullToken.INSTANCE : token);
        return token;
    }

    public static void exitReturn(Object token, Object target) {
        remove(token);
        KairoBridge.exitV2(token, OutcomeEnvelope.ofReturn(target));
    }

    /** Called by bytecode whose protected range starts after {@code super()/this()}. */
    public static Throwable exitThrow(Throwable original) {
        Object token = pop();
        var result = KairoBridge.exitV2(token, OutcomeEnvelope.ofThrow(original));
        if (result.getAction() == BridgeAction.THROW && result.getThrowable() != null) {
            return result.getThrowable();
        }
        return original;
    }

    private static Object pop() {
        Deque<Object> tokens = TOKENS.get();
        Object token = tokens.isEmpty() ? null : tokens.pop();
        clearWhenEmpty(tokens);
        return token == NullToken.INSTANCE ? null : token;
    }

    private static void remove(Object token) {
        Deque<Object> tokens = TOKENS.get();
        Object stored = token == null ? NullToken.INSTANCE : token;
        tokens.removeFirstOccurrence(stored);
        clearWhenEmpty(tokens);
    }

    private static void clearWhenEmpty(Deque<Object> tokens) {
        if (tokens.isEmpty()) {
            TOKENS.remove();
        }
    }

    private enum NullToken { INSTANCE }
}
