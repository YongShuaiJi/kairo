package com.example.kairo.groovy;

import org.codehaus.groovy.control.CompilerConfiguration;

public final class GroovySecurityConfiguration {

    private GroovySecurityConfiguration() {
    }

    public static CompilerConfiguration compilerConfiguration() {
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.setScriptBaseClass(KairoScript.class.getName());
        configuration.addCompilationCustomizers(
                GroovyScriptSecurityPolicy.secureAstCustomizer(),
                new GroovyStructureCustomizer()
        );
        return configuration;
    }
}
