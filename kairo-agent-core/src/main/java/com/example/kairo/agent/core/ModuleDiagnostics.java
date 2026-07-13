package com.example.kairo.agent.core;

import com.example.kairo.core.ClassLoaderIdentity;

import java.lang.instrument.Instrumentation;
import java.lang.module.ModuleDescriptor;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * V1.5 &sect;4.5: discover and report named-module / package-open state, and
 * perform a minimal, audited {@link Instrumentation#redefineModule} when an
 * {@code Advice}/{@code Bridge} access requires a package Kairo's module cannot
 * reach.
 *
 * <p>The agent never does a global open (&sect;4.5: "需要 redefineModule 时采用
 * 最小开放集合并审计，不做全局开放"). Each open is per-package, per-target-module,
 * recorded in {@link #auditLog()} so the platform can surface exactly which
 * packages were opened and why. When the instrumentation cannot redefine modules
 * or the JVM refuses, the helper returns a diagnostic instead of throwing, so a
 * module access failure is reported (fail-open with a clear reason) rather than
 * silently enhancing the wrong target.
 */
public final class ModuleDiagnostics {

    private final Instrumentation instrumentation;
    private final Set<String> auditLog = new CopyOnWriteArraySet<>();

    public ModuleDiagnostics(Instrumentation instrumentation) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
    }

    /** Snapshot of the module state of {@code type}'s defining class. */
    public ModuleInfo moduleInfoOf(Class<?> type) {
        Objects.requireNonNull(type, "type");
        Module module = type.getModule();
        boolean named = module != null && module.isNamed();
        String moduleName = named && module.getName() != null ? module.getName() : "unnamed";
        Set<String> packages = new LinkedHashSet<>();
        if (module != null) {
            packages.addAll(module.getPackages());
        }
        packages.add(type.getPackageName());
        ClassLoader loader = type.getClassLoader();
        String loaderName = loader == null ? "bootstrap" : loader.getClass().getName();
        return new ModuleInfo(moduleName, named, loaderName, packages, true);
    }

    /**
     * Whether {@code target}'s package is open to {@code adviceClass}'s module,
     * so Byte Buddy {@code Advice} hosted in the Kairo module can reflect on the
     * target's private members.
     */
    public boolean isOpenTo(Class<?> target, Class<?> adviceClass) {
        Module targetModule = target.getModule();
        Module adviceModule = adviceClass.getModule();
        String pkg = target.getPackageName();
        if (pkg.isEmpty()) {
            return true;
        }
        return targetModule.isOpen(pkg, adviceModule);
    }

    /**
     * Open one package of {@code target}'s module to {@code adviceClass}'s
     * module via {@link Instrumentation#redefineModule}, with the minimal
     * extra-opens set (only this package &rarr; only this module). Audited.
     * This is the per-package, per-module minimal action (&sect;4.5: no global
     * open). Opening (not merely exporting) is what Byte Buddy {@code Advice}
     * needs to reflect on the target's private members from the Kairo module.
     * Returns a diagnostic describing the outcome; never throws on JVM refusal.
     */
    public String openPackageForAdvice(Class<?> target, Class<?> adviceClass, String reason) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(adviceClass, "adviceClass");
        Module targetModule = target.getModule();
        Module adviceModule = adviceClass.getModule();
        String pkg = target.getPackageName();
        if (pkg.isEmpty()) {
            return "package is the unnamed/default package; no open required";
        }
        if (targetModule.isOpen(pkg, adviceModule)) {
            return "package " + pkg + " already open to " + adviceModule.getName();
        }
        if (!instrumentation.isModifiableModule(targetModule)) {
            return "module " + targetModule.getName() + " is not modifiable; cannot open " + pkg;
        }
        try {
            Set<Module> openTo = new HashSet<>();
            openTo.add(adviceModule);
            java.util.Map<String, Set<Module>> extraOpens = new java.util.LinkedHashMap<>();
            extraOpens.put(pkg, openTo);
            instrumentation.redefineModule(targetModule, Set.of(),
                    java.util.Map.<String, Set<Module>>of(), extraOpens,
                    Set.<Class<?>>of(),
                    java.util.Map.<Class<?>, java.util.List<Class<?>>>of());
            String audit = "opened package " + pkg + " of module " + targetModule.getName()
                    + " to module " + adviceModule.getName() + " (" + reason + ")";
            auditLog.add(audit);
            return audit;
        } catch (UnsupportedOperationException | SecurityException | IllegalStateException e) {
            return "refused to open " + pkg + " of module " + targetModule.getName()
                    + ": " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** The set of minimal-open actions performed, for audit / Web display. */
    public Set<String> auditLog() {
        return Set.copyOf(auditLog);
    }

    /**
     * Produce a human-readable diagnostic for an Advice/Bridge access failure,
     * naming the target class, its module and whether the package is open.
     */
    public String describeAccessFailure(Class<?> target, Class<?> adviceClass, String reason) {
        ModuleInfo info = moduleInfoOf(target);
        boolean open = isOpenTo(target, adviceClass);
        return "Advice/Bridge access to " + target.getName() + " failed (" + reason + "); module="
                + info.moduleName() + " (named=" + info.named() + "), loader="
                + info.classLoaderName() + " (" + ClassLoaderIdentity.idOf(target.getClassLoader())
                + "), packageOpen=" + open;
    }
}
