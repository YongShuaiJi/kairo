package com.example.kairo.core;

import com.example.kairo.api.CallSiteSelector;
import com.example.kairo.api.MethodMetadata;
import com.example.kairo.api.MockDecision;
import com.example.kairo.api.OutcomeState;

import java.util.Arrays;

/**
 * Unified execution state carried from enter to exit across one enhancement
 * invocation. V1.3 extended the V1.2 holder so a single state object tracks the
 * original arguments (before any BEFORE rule mutated them), the current
 * arguments, the original result/throwable produced by the body, and the
 * call-site context for call-site locations.
 *
 * <p>V1.4 adds the frozen per-invocation {@link MethodChainSnapshot} (read once
 * at enter so a mid-invocation publish cannot affect this run), an explicit
 * current throwable and {@link OutcomeState} so return-side rules can replace
 * the outcome and let later rules observe the replacement, and the hit chain
 * revision recorded for audit.
 *
 * <p>The state is mutated only on the single business thread driving the
 * invocation; it is not published across threads.
 */
public final class InvocationState {

    private final MethodKey methodKey;
    private final MethodMetadata method;
    private final Object target;
    private final Object[] originalArguments;
    private Object[] arguments;
    private Object originalResult;
    private Object result;
    private Throwable originalThrowable;
    private Throwable currentThrowable;
    private OutcomeState outcomeState = OutcomeState.PROCEEDING;
    private MockDecision beforeTerminalDecision;

    // frozen per-invocation chain view (V1.4: one snapshot per invocation)
    private MethodChainSnapshot chains = MethodChainSnapshot.EMPTY;
    private long hitChainRevision;

    // call-site context (null for method / constructor locations)
    private final CallSiteSelector callSiteSelector;
    private Object[] callArguments;
    private Object callResult;
    private Throwable callThrowable;

    public InvocationState(MethodKey methodKey, MethodMetadata method, Object target, Object[] arguments) {
        this(methodKey, method, target, arguments, null);
    }

    public InvocationState(MethodKey methodKey, MethodMetadata method, Object target, Object[] arguments,
                           CallSiteSelector callSiteSelector) {
        this.methodKey = methodKey;
        this.method = method;
        this.target = target;
        this.arguments = arguments == null ? new Object[0] : arguments;
        this.originalArguments = this.arguments.clone();
        this.callSiteSelector = callSiteSelector;
    }

    public MethodKey methodKey() {
        return methodKey;
    }

    public MethodMetadata method() {
        return method;
    }

    public Object target() {
        return target;
    }

    public Object[] arguments() {
        return arguments;
    }

    public void arguments(Object[] arguments) {
        this.arguments = arguments;
    }

    /** Arguments as they entered the enhanced construct, before any rule mutated them. */
    public Object[] originalArguments() {
        return originalArguments.clone();
    }

    /** The result produced by the original body before return-side rules ran. */
    public Object originalResult() {
        return originalResult;
    }

    public void originalResult(Object originalResult) {
        this.originalResult = originalResult;
        if (this.result == null) {
            this.result = originalResult;
        }
    }

    /** The current result, possibly already replaced by a return-side rule. */
    public Object result() {
        return result;
    }

    public void result(Object result) {
        this.result = result;
    }

    public Throwable originalThrowable() {
        return originalThrowable;
    }

    public void originalThrowable(Throwable originalThrowable) {
        this.originalThrowable = originalThrowable;
    }

    /** The current throwable, possibly replaced by a return-side rule. */
    public Throwable currentThrowable() {
        return currentThrowable;
    }

    public void currentThrowable(Throwable throwable) {
        this.currentThrowable = throwable;
    }

    /** The current outcome flavour flowing through the chain. */
    public OutcomeState outcomeState() {
        return outcomeState;
    }

    public void outcomeState(OutcomeState outcomeState) {
        this.outcomeState = outcomeState == null ? OutcomeState.PROCEEDING : outcomeState;
    }

    /** Frozen per-invocation chain view (V1.4: one snapshot per invocation). */
    public MethodChainSnapshot chains() {
        return chains;
    }

    public void chains(MethodChainSnapshot chains) {
        this.chains = chains == null ? MethodChainSnapshot.EMPTY : chains;
    }

    /** Revision of the chain snapshot that governed this invocation, for audit. */
    public long hitChainRevision() {
        return hitChainRevision;
    }

    public void hitChainRevision(long hitChainRevision) {
        this.hitChainRevision = hitChainRevision;
    }

    public MockDecision beforeTerminalDecision() {
        return beforeTerminalDecision;
    }

    public void beforeTerminalDecision(MockDecision beforeTerminalDecision) {
        this.beforeTerminalDecision = beforeTerminalDecision;
    }

    public boolean hasBeforeTerminalDecision() {
        return beforeTerminalDecision != null;
    }

    public CallSiteSelector callSiteSelector() {
        return callSiteSelector;
    }

    public boolean isCallSite() {
        return callSiteSelector != null;
    }

    public Object[] callArguments() {
        return callArguments;
    }

    public void callArguments(Object[] callArguments) {
        this.callArguments = callArguments == null ? null : callArguments.clone();
    }

    public Object callResult() {
        return callResult;
    }

    public void callResult(Object callResult) {
        this.callResult = callResult;
    }

    public Throwable callThrowable() {
        return callThrowable;
    }

    public void callThrowable(Throwable callThrowable) {
        this.callThrowable = callThrowable;
    }

    /** Defensive snapshot of the arguments array for diagnostics / recording. */
    public Object[] argumentsSnapshot() {
        return Arrays.copyOf(arguments, arguments.length);
    }
}
