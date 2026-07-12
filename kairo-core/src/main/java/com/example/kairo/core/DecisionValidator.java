package com.example.kairo.core;

import com.example.kairo.api.CallSiteSelector;
import com.example.kairo.api.MethodMetadata;
import com.example.kairo.object.TypeConverter;

public final class DecisionValidator {

    public Object[] validateArguments(MethodMetadata method, Object[] arguments) {
        Class<?>[] parameterTypes = method.parameterTypes();
        if (arguments == null) {
            throw new IllegalArgumentException("arguments must not be null");
        }
        if (parameterTypes.length != arguments.length) {
            throw new IllegalArgumentException("Argument length mismatch, expected "
                    + parameterTypes.length + " but got " + arguments.length);
        }
        Object[] converted = arguments.clone();
        for (int i = 0; i < converted.length; i++) {
            if (!TypeConverter.isAssignable(parameterTypes[i], converted[i])) {
                throw new IllegalArgumentException("Argument " + i + " is not assignable to "
                        + parameterTypes[i].getName());
            }
            converted[i] = TypeConverter.convert(converted[i], parameterTypes[i]);
        }
        return converted;
    }

    public Object validateReturnValue(MethodMetadata method, Object returnValue) {
        Class<?> returnType = method.returnType();
        if (returnType == void.class) {
            if (returnValue != null) {
                throw new IllegalArgumentException("void method cannot return a value");
            }
            return null;
        }
        if (!TypeConverter.isAssignable(returnType, returnValue)) {
            throw new IllegalArgumentException("Return value is not assignable to " + returnType.getName());
        }
        return TypeConverter.convert(returnValue, returnType);
    }

    public Throwable validateThrowable(MethodMetadata method, Throwable throwable) {
        if (throwable == null) {
            throw new IllegalArgumentException("throwable must not be null");
        }
        if (throwable instanceof RuntimeException || throwable instanceof Error) {
            return throwable;
        }
        for (Class<?> exceptionType : method.exceptionTypes()) {
            if (exceptionType.isAssignableFrom(throwable.getClass())) {
                return throwable;
            }
        }
        throw new IllegalArgumentException("Checked exception " + throwable.getClass().getName()
                + " is not declared by " + method);
    }

    // -------------------------------------------------------- V1.3 call-site validation

    /**
     * Validate arguments a call-site BEFORE rule wants to pass to the callee,
     * resolved from the callee descriptor carried by the selector. The loader is
     * the caller method's defining loader, so callee business types resolve the
     * same way they do for the original invoke.
     */
    public Object[] validateCallArguments(CallSiteSelector selector, Object[] callArguments, ClassLoader loader) {
        Class<?>[] parameterTypes = MethodDescriptorTypes.parameterTypes(selector.descriptor(), loader);
        if (callArguments == null) {
            throw new IllegalArgumentException("call arguments must not be null");
        }
        if (parameterTypes.length != callArguments.length) {
            throw new IllegalArgumentException("Call argument length mismatch, expected "
                    + parameterTypes.length + " but got " + callArguments.length);
        }
        Object[] converted = callArguments.clone();
        for (int i = 0; i < converted.length; i++) {
            if (!TypeConverter.isAssignable(parameterTypes[i], converted[i])) {
                throw new IllegalArgumentException("Call argument " + i + " is not assignable to "
                        + parameterTypes[i].getName());
            }
            converted[i] = TypeConverter.convert(converted[i], parameterTypes[i]);
        }
        return converted;
    }

    /**
     * Validate the replacement result a call-site RETURN rule produces, resolved
     * from the callee descriptor. A void callee rejects a non-null result.
     */
    public Object validateCallResult(CallSiteSelector selector, Object callResult, ClassLoader loader) {
        Class<?> returnType = MethodDescriptorTypes.returnType(selector.descriptor(), loader);
        if (returnType == void.class) {
            if (callResult != null) {
                throw new IllegalArgumentException("void callee cannot return a value");
            }
            return null;
        }
        if (!TypeConverter.isAssignable(returnType, callResult)) {
            throw new IllegalArgumentException("Call result is not assignable to " + returnType.getName());
        }
        return TypeConverter.convert(callResult, returnType);
    }
}
