package com.example.kairo.groovy;

public interface ScriptCompiler {

    CompiledMockScript compile(String ruleId, long version, String script);
}
