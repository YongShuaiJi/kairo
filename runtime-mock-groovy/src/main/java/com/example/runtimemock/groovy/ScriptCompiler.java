package com.example.runtimemock.groovy;

public interface ScriptCompiler {

    CompiledMockScript compile(String ruleId, long version, String script);
}
