package com.example.kairo.groovy;

import com.example.kairo.api.CapabilityProfile;
import org.codehaus.groovy.control.CompilerConfiguration;

import java.util.Set;

/**
 * UNRESTRICTED tier policy: installs no security AST deny-list and no structure
 * customizer, so IO, networking, reflection, threads and target application classes are
 * all permitted.
 *
 * <p>The tier-shared limits &mdash; maximum script byte size and maximum compiled artifact
 * byte size &mdash; are still enforced, but by {@link GroovyScriptCompiler} uniformly for
 * every tier (read from {@link ScriptCompilationContext}), not by this policy. Source
 * validation is therefore a no-op here.
 */
public final class UnrestrictedScriptPolicy implements ScriptSecurityPolicy {

    private static final UnrestrictedScriptPolicy INSTANCE = new UnrestrictedScriptPolicy();

    /** Stateless singleton. */
    public static UnrestrictedScriptPolicy instance() {
        return INSTANCE;
    }

    private UnrestrictedScriptPolicy() {
    }

    @Override
    public CapabilityProfile profile() {
        return CapabilityProfile.UNRESTRICTED;
    }

    @Override
    public void validateSource(String script) {
        // No tier-specific source checks: the shared script-byte limit is enforced by
        // the compiler from ScriptCompilationContext for every tier.
    }

    @Override
    public void applyTo(CompilerConfiguration configuration) {
        // Intentionally installs no SecureASTCustomizer and no GroovyStructureCustomizer.
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
