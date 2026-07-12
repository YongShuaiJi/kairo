package com.example.kairo.core;

import com.example.kairo.api.CallSiteSelector;
import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.InvokeOpcode;
import com.example.kairo.api.MethodMetadata;
import com.example.kairo.bridge.BridgeAction;
import com.example.kairo.bridge.BridgeDispatcher;
import com.example.kairo.bridge.EnterResult;
import com.example.kairo.bridge.ExitResult;
import com.example.kairo.bridge.InvocationEnvelope;
import com.example.kairo.bridge.OutcomeEnvelope;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges injected advice into the {@link RuleDispatcher}.
 *
 * <p>V1.3 adds the V2 entry points ({@link #onEnterV2} / {@link #onExitV2}) that
 * serve constructor and call-site locations, which the V1
 * {@code (Class, Method, ...)} signature cannot express. The V2 path resolves a
 * reflective constructor / caller method from the envelope, builds the call-site
 * selector when applicable, prepares an {@link InvocationState} and delegates to
 * the same location-aware dispatcher as the V1 method path.
 *
 * <p>Reflective resolution results are cached per (class, member, descriptor) so
 * the hot call-site / constructor path does not repeat {@code getDeclaredMethod}
 * lookups.
 */
public final class AgentBridgeDispatcher implements BridgeDispatcher {

    private final RuleDispatcher ruleDispatcher;
    private final InvocationObserver invocationObserver;
    private final ConcurrentHashMap<String, MethodMetadata> metadataCache = new ConcurrentHashMap<>();

    public AgentBridgeDispatcher(RuleDispatcher ruleDispatcher) {
        this(ruleDispatcher, InvocationObserver.NOOP);
    }

    public AgentBridgeDispatcher(RuleDispatcher ruleDispatcher, InvocationObserver invocationObserver) {
        this.ruleDispatcher = Objects.requireNonNull(ruleDispatcher, "ruleDispatcher");
        this.invocationObserver = invocationObserver == null ? InvocationObserver.NOOP : invocationObserver;
    }

    @Override
    public EnterResult onEnter(Class<?> declaringClass, Method method, Object target, Object[] arguments) {
        MethodKey methodKey = MethodKey.of(method);
        MethodMetadata metadata = new MethodMetadata(method, MethodDescriptor.of(method));
        EnterResult ruleResult = ruleDispatcher.onEnter(methodKey, metadata, target, arguments);
        Object[] effectiveArguments = ruleResult.getArguments() == null ? arguments : ruleResult.getArguments();
        Object observerToken = invocationObserver.onEnter(methodKey, metadata, target, effectiveArguments);
        if (observerToken == null) {
            return ruleResult;
        }
        CompositeInvocationToken token = new CompositeInvocationToken(
                ruleResult.getInvocationToken(), observerToken);
        return switch (ruleResult.getAction()) {
            case PROCEED -> EnterResult.proceed(token, ruleResult.getArguments());
            case RETURN -> EnterResult.returnValue(token, ruleResult.getArguments(), ruleResult.getReturnValue());
            case THROW -> EnterResult.throwException(token, ruleResult.getArguments(), ruleResult.getThrowable());
        };
    }

    @Override
    public ExitResult onExit(Object invocationToken, Object returnValue, Throwable throwable) {
        if (invocationToken instanceof CompositeInvocationToken composite) {
            ExitResult ruleResult = composite.ruleToken() instanceof InvocationState state
                    ? ruleDispatcher.onExit(state, returnValue, throwable)
                    : ExitResult.proceed();
            Object effectiveReturnValue = returnValue;
            Throwable effectiveThrowable = throwable;
            if (ruleResult.getAction() == BridgeAction.RETURN) {
                effectiveReturnValue = ruleResult.getReturnValue();
                effectiveThrowable = null;
            } else if (ruleResult.getAction() == BridgeAction.THROW) {
                effectiveThrowable = ruleResult.getThrowable();
            }
            invocationObserver.onExit(composite.observerToken(), effectiveReturnValue, effectiveThrowable);
            return ruleResult;
        }
        if (!(invocationToken instanceof InvocationState state)) {
            return ExitResult.proceed();
        }
        return ruleDispatcher.onExit(state, returnValue, throwable);
    }

    // -------------------------------------------------------- V1.3 constructor / call-site

    @Override
    public EnterResult onEnterV2(InvocationEnvelope envelope) {
        EnhancementLocation location = EnhancementLocation.valueOf(envelope.getLocation());
        MethodMetadata metadata = resolveMetadata(envelope, location);
        MethodKey methodKey = new MethodKey(envelope.getDeclaringClass(),
                envelope.getMemberName(), envelope.getDescriptor());
        Object[] arguments = envelope.getArguments() == null ? new Object[0] : envelope.getArguments();
        CallSiteSelector callSiteSelector = null;
        if (envelope.isCallSite()) {
            callSiteSelector = CallSiteSelector.builder()
                    .owner(envelope.getCallOwner())
                    .name(envelope.getCallName())
                    .descriptor(envelope.getCallDescriptor())
                    .opcode(InvokeOpcode.fromOpcode(envelope.getCallOpcode()))
                    .occurrenceIndex(envelope.getCallOccurrenceIndex())
                    .build();
            arguments = envelope.getCallArguments() == null ? new Object[0] : envelope.getCallArguments();
        }
        InvocationState state = new InvocationState(methodKey, metadata, envelope.getTarget(),
                arguments, callSiteSelector);
        if (callSiteSelector != null) {
            state.callArguments(arguments);
        }
        return ruleDispatcher.enter(state, location);
    }

    @Override
    public ExitResult onExitV2(Object invocationToken, OutcomeEnvelope outcome) {
        if (!(invocationToken instanceof InvocationState state)) {
            return ExitResult.proceed();
        }
        Object returnValue = outcome.isThrow() ? null : outcome.getReturnValue();
        Throwable throwable = outcome.isThrow() ? outcome.getThrowable() : null;
        return ruleDispatcher.onExit(state, returnValue, throwable);
    }

    private MethodMetadata resolveMetadata(InvocationEnvelope envelope, EnhancementLocation location) {
        String cacheKey = envelope.getDeclaringClass().getName() + "#"
                + envelope.getMemberName() + envelope.getDescriptor() + "@" + location;
        MethodMetadata cached = metadataCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        MethodMetadata resolved;
        if (location.isConstructorLocation()) {
            Constructor<?> constructor = resolveConstructor(envelope);
            resolved = MethodMetadata.forConstructor(constructor, envelope.getDescriptor());
        } else {
            Method method = resolveMethod(envelope);
            resolved = new MethodMetadata(method, envelope.getDescriptor());
        }
        metadataCache.putIfAbsent(cacheKey, resolved);
        return resolved;
    }

    private Constructor<?> resolveConstructor(InvocationEnvelope envelope) {
        Class<?>[] paramTypes = MethodDescriptorTypes.parameterTypes(
                envelope.getDescriptor(), envelope.getDeclaringClass().getClassLoader());
        try {
            return envelope.getDeclaringClass().getDeclaredConstructor(paramTypes);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Constructor not found: "
                    + envelope.getDeclaringClass().getName() + envelope.getDescriptor(), e);
        }
    }

    private Method resolveMethod(InvocationEnvelope envelope) {
        Class<?>[] paramTypes = MethodDescriptorTypes.parameterTypes(
                envelope.getDescriptor(), envelope.getDeclaringClass().getClassLoader());
        try {
            return envelope.getDeclaringClass().getDeclaredMethod(envelope.getMemberName(), paramTypes);
        } catch (NoSuchMethodException e) {
            try {
                return envelope.getDeclaringClass().getMethod(envelope.getMemberName(), paramTypes);
            } catch (NoSuchMethodException ignored) {
                throw new IllegalArgumentException("Method not found: "
                        + envelope.getDeclaringClass().getName() + "#"
                        + envelope.getMemberName() + envelope.getDescriptor(), e);
            }
        }
    }

    private record CompositeInvocationToken(Object ruleToken, Object observerToken) {
    }
}
