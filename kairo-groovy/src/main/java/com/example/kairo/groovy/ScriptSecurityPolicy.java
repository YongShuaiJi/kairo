package com.example.kairo.groovy;

import com.example.kairo.api.CapabilityProfile;
import org.codehaus.groovy.control.CompilerConfiguration;

import java.util.Set;

/**
 * Security policy applied to one Groovy script compilation.
 *
 * <p>A policy owns three concerns, kept deliberately separate from the shared
 * compilation limits (script byte size and compiled artifact byte size, which are
 * enforced by {@link GroovyScriptCompiler} for <em>every</em> tier):
 * <ul>
 *   <li>{@link #validateSource(String)} &mdash; tier-specific source checks such as
 *       forbidden markers, block nesting, statement counts and import allow-lists.</li>
 *   <li>{@link #applyTo(CompilerConfiguration)} &mdash; the AST customizers installed for
 *       the tier. {@link CapabilityProfile#SAFE} and {@link CapabilityProfile#EXTENDED}
 *       install the sensitive-capability deny-list; {@link CapabilityProfile#UNRESTRICTED}
 *       installs none.</li>
 *   <li>{@link #allowedPackages()} / {@link #allowedClasses()} &mdash; the explicit
 *       business packages/classes the script is additionally permitted to use.</li>
 * </ul>
 */
public interface ScriptSecurityPolicy {

    /** Capability tier this policy implements. */
    CapabilityProfile profile();

    /**
     * Validate tier-specific source constraints before compilation.
     *
     * <p>This must <em>not</em> enforce the shared script-byte limit; that limit is
     * enforced uniformly by the compiler from {@link ScriptCompilationContext}.
     */
    void validateSource(String script);

    /**
     * Add this tier's AST customizers to the configuration. The script base class
     * ({@link KairoScript}) is set by the compiler and must not be set here.
     */
    void applyTo(CompilerConfiguration configuration);

    /**
     * Explicitly permitted extra packages (e.g. {@code com.example.biz}).
     * Empty for {@link CapabilityProfile#SAFE} and {@link CapabilityProfile#UNRESTRICTED}.
     */
    Set<String> allowedPackages();

    /**
     * Explicitly permitted extra classes (e.g. {@code com.example.biz.Foo}).
     * Empty for {@link CapabilityProfile#SAFE} and {@link CapabilityProfile#UNRESTRICTED}.
     */
    Set<String> allowedClasses();

    /**
     * Resolve the policy for a compilation context. The {@link CapabilityProfile#EXTENDED}
     * policy is built from the context's declared packages/classes.
     */
    static ScriptSecurityPolicy forContext(ScriptCompilationContext context) {
        java.util.Objects.requireNonNull(context, "context");
        switch (context.profile()) {
            case SAFE:
                return SafeScriptPolicy.instance();
            case EXTENDED:
                return new ExtendedScriptPolicy(context.allowedPackages(), context.allowedClasses());
            case UNRESTRICTED:
                return UnrestrictedScriptPolicy.instance();
            default:
                throw new IllegalArgumentException("Unsupported capability profile: " + context.profile());
        }
    }
}
