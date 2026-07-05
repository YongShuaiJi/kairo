package com.example.kairo.bridge;

public final class EnterResult {

    private static final EnterResult PROCEED_WITHOUT_CONTEXT =
            new EnterResult(null, null, false, BridgeAction.PROCEED, null, null);

    private final Object invocationToken;
    private final Object[] arguments;
    private final boolean skipOriginalMethod;
    private final BridgeAction action;
    private final Object returnValue;
    private final Throwable throwable;

    private EnterResult(Object invocationToken, Object[] arguments, boolean skipOriginalMethod,
                        BridgeAction action, Object returnValue, Throwable throwable) {
        this.invocationToken = invocationToken;
        this.arguments = arguments;
        this.skipOriginalMethod = skipOriginalMethod;
        this.action = action;
        this.returnValue = returnValue;
        this.throwable = throwable;
    }

    public static EnterResult proceedWithoutContext() {
        return PROCEED_WITHOUT_CONTEXT;
    }

    public static EnterResult proceed(Object invocationToken, Object[] arguments) {
        return new EnterResult(invocationToken, arguments, false, BridgeAction.PROCEED, null, null);
    }

    public static EnterResult returnValue(Object invocationToken, Object[] arguments, Object returnValue) {
        return new EnterResult(invocationToken, arguments, true, BridgeAction.RETURN, returnValue, null);
    }

    public static EnterResult throwException(Object invocationToken, Object[] arguments, Throwable throwable) {
        return new EnterResult(invocationToken, arguments, true, BridgeAction.THROW, null, throwable);
    }

    public Object getInvocationToken() {
        return invocationToken;
    }

    public Object[] getArguments() {
        return arguments;
    }

    public boolean isSkipOriginalMethod() {
        return skipOriginalMethod;
    }

    public BridgeAction getAction() {
        return action;
    }

    public Object getReturnValue() {
        return returnValue;
    }

    public Throwable getThrowable() {
        return throwable;
    }
}
