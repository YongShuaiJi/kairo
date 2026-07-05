package com.example.kairo.agent.core;

import com.example.kairo.bridge.BridgeAction;
import com.example.kairo.bridge.EnterResult;
import com.example.kairo.bridge.ExitResult;
import com.example.kairo.bridge.KairoBridge;
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
            @Advice.Local("kairoToken") Object token
    ) {
        EnterResult result = KairoBridge.enter(declaringClass, method, target, arguments);
        token = result.getInvocationToken();
        if (result.getArguments() != null) {
            arguments = result.getArguments();
        }
        return result.isSkipOriginalMethod();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
            @Advice.Local("kairoToken") Object token,
            @Advice.Thrown(readOnly = false, typing = Assigner.Typing.DYNAMIC) Throwable throwable
    ) {
        ExitResult result = KairoBridge.exit(token, null, throwable);
        if (result.getAction() == BridgeAction.RETURN) {
            throwable = null;
        } else if (result.getAction() == BridgeAction.THROW) {
            throwable = result.getThrowable();
        }
    }
}
