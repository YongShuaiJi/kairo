package com.example.kairo.agent.core;

import com.example.kairo.bridge.BridgeAction;
import com.example.kairo.bridge.EnterResult;
import com.example.kairo.bridge.ExitResult;
import com.example.kairo.bridge.InvocationEnvelope;
import com.example.kairo.bridge.KairoBridge;
import com.example.kairo.bridge.OutcomeEnvelope;
import com.example.kairo.core.MethodDescriptorTypes;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Runtime helper invoked by the straight-line shim the {@link CallSiteVisitorWrapper}
 * weaves around an enhanced call site.
 *
 * <p>The shim only saves the invoke operands, builds an {@code Object[]} argument
 * array, calls {@link #invoke} and unboxes the result &mdash; it has no branches
 * or exception handlers, so it needs no stack-map frames and the method's existing
 * frames stay valid. All control flow (CALL_BEFORE short-circuit, the original
 * invocation, CALL_RETURN / CALL_THROW handling) lives here.
 *
 * <p>The original invocation is performed reflectively via a cached {@link Method}.
 * For {@code invokevirtual}/{@code invokestatic}/{@code invokeinterface} and
 * private {@code invokespecial} this preserves semantics; super-invocation
 * ({@code invokespecial} to a superclass) dispatches virtually, which is a
 * documented V1.3 limitation for that rare case.
 */
public final class CallSiteBridge {

    public static final int ACTION_PROCEED = 0;
    public static final int ACTION_RETURN = 1;
    public static final int ACTION_THROW = 2;

    private static final ConcurrentMap<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    private CallSiteBridge() {
    }

    public static final class CallOutcome {
        final int action;
        final Object value;
        final Throwable throwable;

        private CallOutcome(int action, Object value, Throwable throwable) {
            this.action = action;
            this.value = value;
            this.throwable = throwable;
        }

        public int action() {
            return action;
        }

        public Object value() {
            return value;
        }

        public Throwable throwable() {
            return throwable;
        }
    }

    /**
     * Run the around-call-site logic for one enhanced invoke. Returns the call
     * result (boxed) or throws the throwable a rule produced.
     */
    public static Object invoke(String callerClassName, String callerMethodName, String callerDescriptor,
                                Class<?> callerClass, String owner, String name, String descriptor,
                                int opcode, int occurrence, Object receiver, Object[] arguments) throws Throwable {
        EnterResult enter = before(callerClassName, callerMethodName, callerDescriptor, callerClass,
                owner, name, descriptor, opcode, occurrence, receiver, arguments);
        Object token = enter.getInvocationToken();
        int action = actionCode(enter.getAction());
        if (action == ACTION_THROW) {
            // CALL_BEFORE short-circuited with a throw: the call never ran, so no
            // CALL_RETURN/CALL_THROW exit fires.
            throw enter.getThrowable();
        }
        if (action == ACTION_RETURN) {
            // CALL_BEFORE short-circuited with a return value; run CALL_RETURN on it.
            return applyOutcome(afterSkipReturn(token, enter.getReturnValue()));
        }
        // PROCEED: run the original invocation. A CALL_BEFORE rule may have replaced the
        // call arguments, so use the effective arguments from the enter result.
        Object[] effectiveArguments = enter.getArguments() != null ? enter.getArguments() : arguments;
        Object result;
        try {
            result = invokeOriginal(owner, name, descriptor, callerClass, receiver, effectiveArguments);
        } catch (Throwable throwable) {
            return applyOutcome(afterThrow(token, throwable));
        }
        return applyOutcome(afterReturn(token, result));
    }

    /** Run the CALL_BEFORE rules; returns the enter result. */
    public static EnterResult before(String callerClassName, String callerMethodName, String callerDescriptor,
                                     Class<?> callerClass, String owner, String name, String descriptor,
                                     int opcode, int occurrence, Object receiver, Object[] arguments) {
        InvocationEnvelope envelope = InvocationEnvelope.builder()
                .location("CALL_BEFORE")
                .declaringClass(callerClass)
                .memberName(callerMethodName)
                .descriptor(callerDescriptor)
                .constructor(false)
                .target(receiver)
                .arguments(arguments)
                .callOwner(owner)
                .callName(name)
                .callDescriptor(descriptor)
                .callOpcode(opcode)
                .callOccurrenceIndex(occurrence)
                .callArguments(arguments)
                .build();
        return KairoBridge.enterV2(envelope);
    }

    public static CallOutcome afterReturn(Object token, Object result) {
        ExitResult exit = KairoBridge.exitV2(token, OutcomeEnvelope.ofReturn(result));
        if (exit.getAction() == BridgeAction.RETURN) {
            return new CallOutcome(ACTION_RETURN, exit.getReturnValue(), null);
        }
        if (exit.getAction() == BridgeAction.THROW) {
            return new CallOutcome(ACTION_THROW, null, exit.getThrowable());
        }
        return new CallOutcome(ACTION_PROCEED, result, null);
    }

    public static CallOutcome afterThrow(Object token, Throwable throwable) {
        ExitResult exit = KairoBridge.exitV2(token, OutcomeEnvelope.ofThrow(throwable));
        if (exit.getAction() == BridgeAction.RETURN) {
            return new CallOutcome(ACTION_RETURN, exit.getReturnValue(), null);
        }
        if (exit.getAction() == BridgeAction.THROW) {
            return new CallOutcome(ACTION_THROW, null, exit.getThrowable());
        }
        // PROCEED on a thrown call: rethrow the original throwable.
        return new CallOutcome(ACTION_THROW, null, throwable);
    }

    public static CallOutcome afterSkipReturn(Object token, Object returnValue) {
        ExitResult exit = KairoBridge.exitV2(token, OutcomeEnvelope.ofReturn(returnValue));
        if (exit.getAction() == BridgeAction.RETURN) {
            return new CallOutcome(ACTION_RETURN, exit.getReturnValue(), null);
        }
        if (exit.getAction() == BridgeAction.THROW) {
            return new CallOutcome(ACTION_THROW, null, exit.getThrowable());
        }
        return new CallOutcome(ACTION_PROCEED, returnValue, null);
    }

    private static Object applyOutcome(CallOutcome outcome) throws Throwable {
        if (outcome.action == ACTION_THROW) {
            throw outcome.throwable;
        }
        return outcome.value;
    }

    private static int actionCode(BridgeAction action) {
        if (action == BridgeAction.RETURN) {
            return ACTION_RETURN;
        }
        if (action == BridgeAction.THROW) {
            return ACTION_THROW;
        }
        return ACTION_PROCEED;
    }

    private static Object invokeOriginal(String owner, String name, String descriptor,
                                         Class<?> callerClass, Object receiver, Object[] arguments) throws Throwable {
        String key = owner + "#" + name + descriptor;
        Method method = METHOD_CACHE.get(key);
        if (method == null) {
            Class<?> ownerClass = Class.forName(owner.replace('/', '.'), false,
                    callerClass.getClassLoader() == null ? ClassLoader.getSystemClassLoader() : callerClass.getClassLoader());
            Class<?>[] paramTypes = MethodDescriptorTypes.parameterTypes(descriptor, callerClass.getClassLoader());
            try {
                method = ownerClass.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException e) {
                method = ownerClass.getMethod(name, paramTypes);
            }
            method.setAccessible(true);
            Method existing = METHOD_CACHE.putIfAbsent(key, method);
            if (existing != null) {
                method = existing;
            }
        }
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException ite) {
            throw ite.getCause();
        }
    }
}
