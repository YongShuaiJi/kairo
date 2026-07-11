package com.example.kairo.groovy;

import com.example.kairo.api.CapabilityProfile;
import org.codehaus.groovy.control.CompilerConfiguration;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EXTENDED tier policy: an explicit allow-list of business packages/classes the
 * script may additionally use, layered on top of the SAFE sensitive-capability floor.
 *
 * <p>Design:
 * <ul>
 *   <li>The sensitive floor (java.io, java.net, reflection, threads, processes,
 *       classloaders, groovy.lang, net.bytebuddy, javax, sun, com.sun, ...) is reused
 *       verbatim from {@link GroovyScriptSecurityPolicy}. It uses precise fully-qualified
 *       name / package-prefix matching &mdash; not a crude substring blacklist &mdash; so an
 *       authorized class such as {@code com.example.io.SafeFile} is never collateral damage
 *       of a {@code java.io.} rule.</li>
 *   <li>The floor is a hard floor: configuration that attempts to allow a sensitive class
 *       is rejected at construction time, and the AST gate below checks the floor first,
 *       so a sensitive type is denied even if a too-broad package were configured.</li>
 *   <li>Explicit {@code import} statements are gated by a source-level allow-list: only
 *       configured packages/classes, the safe {@code java.*} baseline (minus sensitive
 *       sub-packages), and the {@code com.example.kairo.*} API may be imported. Imports of
 *       sensitive classes are rejected (the floor wins).</li>
 *   <li>An independent AST allow-list gate ({@link ExtendedTypeAllowListCustomizer}) runs
 *       during semantic analysis and gates <em>every</em> author-written type reference
 *       &mdash; imports, class expressions, constructors, static receivers, annotations,
 *       casts, declarations, generics and arrays &mdash; against the same baseline plus
 *       configured classes/packages. This closes the FQN direct-usage bypass: a
 *       non-sensitive class that is neither baseline nor configured is rejected at
 *       compile time regardless of how it is referenced.</li>
 * </ul>
 */
public final class ExtendedScriptPolicy implements ScriptSecurityPolicy {

    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "(?m)^\\s*import\\s+(static\\s+)?([A-Za-z_$][\\w.$]*\\*?)\\s*(?:as\\s+\\w+)?\\s*;?\\s*$");

    private final Set<String> allowedPackages;
    private final Set<String> allowedClasses;

    public ExtendedScriptPolicy(Set<String> allowedPackages, Set<String> allowedClasses) {
        this.allowedPackages = Set.copyOf(requireNoneSensitive(allowedPackages, "allowedPackages"));
        this.allowedClasses = Set.copyOf(requireNoneSensitive(allowedClasses, "allowedClasses"));
    }

    private static Set<String> requireNoneSensitive(Set<String> entries, String name) {
        if (entries == null) {
            return Set.of();
        }
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                throw new IllegalArgumentException(name + " must not contain blank entries");
            }
            String normalized = normalizePackageOrClass(entry.trim());
            if (GroovyScriptSecurityPolicy.isSensitiveType(normalized)) {
                throw new IllegalArgumentException(
                        name + " must not include a sensitive capability: " + entry);
            }
        }
        return entries;
    }

    @Override
    public CapabilityProfile profile() {
        return CapabilityProfile.EXTENDED;
    }

    @Override
    public void validateSource(String script) {
        GroovyScriptSecurityPolicy.validateSource(script);
        validateImports(script);
    }

    @Override
    public void applyTo(CompilerConfiguration configuration) {
        // The SAFE sensitive deny-list runs first: it keeps the hard floor (IO, network,
        // reflection, threads, processes, classloaders, groovy.lang, ...) and preserves
        // the method/property/statement defenses and the historical "Expression" reject
        // message for sensitive constructor calls.
        // The independent allow-list gate runs second and closes the FQN direct-usage
        // bypass: a non-sensitive class neither on the SAFE baseline nor in the configured
        // allow-list is rejected no matter which AST shape references it.
        configuration.addCompilationCustomizers(
                GroovyScriptSecurityPolicy.secureAstCustomizer(),
                new ExtendedTypeAllowListCustomizer(allowedPackages, allowedClasses),
                new GroovyStructureCustomizer()
        );
    }

    @Override
    public Set<String> allowedPackages() {
        return allowedPackages;
    }

    @Override
    public Set<String> allowedClasses() {
        return allowedClasses;
    }

    private void validateImports(String script) {
        Matcher matcher = IMPORT_PATTERN.matcher(script);
        while (matcher.find()) {
            boolean isStatic = matcher.group(1) != null;
            String path = normalizeImportPath(isStatic, matcher.group(2));
            if (path.isBlank()) {
                continue;
            }
            if (GroovyScriptSecurityPolicy.isSensitiveType(path)) {
                throw new IllegalArgumentException("Forbidden EXTENDED import (sensitive): " + path);
            }
            if (!isImportable(path)) {
                throw new IllegalArgumentException(
                        "Forbidden EXTENDED import (not declared in allowedPackages/allowedClasses): " + path);
            }
        }
    }

    private boolean isImportable(String path) {
        if (allowedClasses.contains(path)) {
            return true;
        }
        for (String pkg : allowedPackages) {
            if (path.equals(pkg) || path.startsWith(pkg + ".")) {
                return true;
            }
        }
        // Safe baseline: the standard library minus sensitive sub-packages, and the
        // Kairo script API. Everything else must be explicitly declared.
        if (path.startsWith("java.") && !GroovyScriptSecurityPolicy.isSensitiveType(path)) {
            return true;
        }
        return path.startsWith("com.example.kairo.");
    }

    private static String normalizeImportPath(boolean isStatic, String raw) {
        String p = raw.trim();
        boolean star = p.endsWith(".*");
        if (star) {
            p = p.substring(0, p.length() - 2);
        }
        if (isStatic && !star && p.indexOf('.') > 0) {
            int dot = p.lastIndexOf('.');
            p = p.substring(0, dot);
        }
        if (p.endsWith(".")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String normalizePackageOrClass(String entry) {
        String p = entry.trim();
        if (p.endsWith(".*")) {
            p = p.substring(0, p.length() - 2);
        }
        if (p.endsWith(".")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
