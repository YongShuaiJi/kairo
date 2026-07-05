package com.example.kairo.agent.core;

import com.example.kairo.api.MockRule;
import com.example.kairo.core.CompiledRule;
import com.example.kairo.core.MethodKey;

import java.lang.reflect.Method;

record PublishedRule(Method method, MethodKey methodKey, MockRule rule, CompiledRule compiledRule) {
}
