package com.example.runtimemock.groovy;

import org.codehaus.groovy.control.CompilerConfiguration;

public final class GroovySecurityConfiguration {

    private GroovySecurityConfiguration() {
    }

    public static CompilerConfiguration compilerConfiguration() {
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.setScriptBaseClass(RuntimeMockScript.class.getName());
        configuration.addCompilationCustomizers(GroovyScriptSecurityPolicy.secureAstCustomizer());
        return configuration;
    }
}
