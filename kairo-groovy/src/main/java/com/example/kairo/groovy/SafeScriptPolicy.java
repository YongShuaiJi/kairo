package com.example.kairo.groovy;

import com.example.kairo.api.CapabilityProfile;
import org.codehaus.groovy.control.CompilerConfiguration;

import java.util.Set;

/**
 * SAFE tier policy: the strict default. Reuses every source check, the
 * {@link org.codehaus.groovy.control.customizers.SecureASTCustomizer} sensitive-capability
 * deny-list, and the expression/statement checkers defined by
 * {@link GroovyScriptSecurityPolicy}, so the historical sandbox-bypass behavior is
 * preserved exactly. No extra packages or classes are permitted.
 */
public final class SafeScriptPolicy implements ScriptSecurityPolicy {

    private static final SafeScriptPolicy INSTANCE = new SafeScriptPolicy();

    /** Stateless singleton. */
    public static SafeScriptPolicy instance() {
        return INSTANCE;
    }

    private SafeScriptPolicy() {
    }

    @Override
    public CapabilityProfile profile() {
        return CapabilityProfile.SAFE;
    }

    @Override
    public void validateSource(String script) {
        GroovyScriptSecurityPolicy.validateSource(script);
    }

    @Override
    public void applyTo(CompilerConfiguration configuration) {
        configuration.addCompilationCustomizers(
                GroovyScriptSecurityPolicy.secureAstCustomizer(),
                new GroovyStructureCustomizer()
        );
    }

    @Override
    public Set<String> allowedPackages() {
        return Set.of();
    }

    @Override
    public Set<String> allowedClasses() {
        return Set.of();
    }
}
