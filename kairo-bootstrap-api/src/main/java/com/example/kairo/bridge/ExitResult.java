package com.example.kairo.bridge;

public final class ExitResult {

    private static final ExitResult PROCEED = new ExitResult(BridgeAction.PROCEED, null, null);

    private final BridgeAction action;
    private final Object returnValue;
    private final Throwable throwable;

    private ExitResult(BridgeAction action, Object returnValue, Throwable throwable) {
        this.action = action;
        this.returnValue = returnValue;
        this.throwable = throwable;
    }

    public static ExitResult proceed() {
        return PROCEED;
    }

    public static ExitResult returnValue(Object returnValue) {
        return new ExitResult(BridgeAction.RETURN, returnValue, null);
    }

    public static ExitResult throwException(Throwable throwable) {
        return new ExitResult(BridgeAction.THROW, null, throwable);
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
