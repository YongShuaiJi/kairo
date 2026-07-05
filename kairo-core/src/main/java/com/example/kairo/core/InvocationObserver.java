package com.example.kairo.core;

import com.example.kairo.api.MethodMetadata;

public interface InvocationObserver {

    InvocationObserver NOOP = new InvocationObserver() {
        @Override
        public Object onEnter(MethodKey methodKey, MethodMetadata method, Object target, Object[] arguments) {
            return null;
        }

        @Override
        public void onExit(Object token, Object returnValue, Throwable throwable) {
        }
    };

    Object onEnter(MethodKey methodKey, MethodMetadata method, Object target, Object[] arguments);

    void onExit(Object token, Object returnValue, Throwable throwable);
}
