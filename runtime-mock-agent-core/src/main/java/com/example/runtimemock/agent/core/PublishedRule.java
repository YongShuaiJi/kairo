package com.example.runtimemock.agent.core;

import com.example.runtimemock.api.MockRule;
import com.example.runtimemock.core.CompiledRule;
import com.example.runtimemock.core.MethodKey;

import java.lang.reflect.Method;

record PublishedRule(Method method, MethodKey methodKey, MockRule rule, CompiledRule compiledRule) {
}
