package com.example.runtimemock.agent.core;

import com.example.runtimemock.bridge.BridgeAction;
import com.example.runtimemock.bridge.EnterResult;
import com.example.runtimemock.bridge.ExitResult;
import com.example.runtimemock.bridge.RuntimeMockBridge;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public final class VoidMethodAdvice {

    private VoidMethodAdvice() {
    }

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static boolean enter(
            @Advice.Origin Class<?> declaringClass,
            @Advice.Origin java.lang.reflect.Method method,
            @Advice.This(optional = true, typing = Assigner.Typing.DYNAMIC) Object target,
            @Advice.AllArguments(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object[] arguments,
            @Advice.Local("runtimeMockToken") Object token
    ) {
        EnterResult result = RuntimeMockBridge.enter(declaringClass, method, target, arguments);
        token = result.getInvocationToken();
        if (result.getArguments() != null) {
            arguments = result.getArguments();
        }
        return result.isSkipOriginalMethod();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
            @Advice.Local("runtimeMockToken") Object token,
            @Advice.Thrown(readOnly = false, typing = Assigner.Typing.DYNAMIC) Throwable throwable
    ) {
        ExitResult result = RuntimeMockBridge.exit(token, null, throwable);
        if (result.getAction() == BridgeAction.RETURN) {
            throwable = null;
        } else if (result.getAction() == BridgeAction.THROW) {
            throwable = result.getThrowable();
        }
    }
}
