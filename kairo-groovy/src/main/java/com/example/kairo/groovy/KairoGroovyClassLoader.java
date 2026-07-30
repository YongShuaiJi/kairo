package com.example.kairo.groovy;

import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;

/**
 * GroovyClassLoader that captures the total bytecode size of every class generated during
 * a single {@code parseClass} call, so the tier-shared compiled-artifact size limit can be
 * enforced. The capture hooks {@link #createCollector} &mdash; the same extension point
 * Groovy uses internally &mdash; and sums the {@code byte[]} handed to
 * {@code ClassCollector.createClass}. Security customizers in the {@link CompilerConfiguration}
 * run during earlier compilation phases and are unaffected.
 */
final class KairoGroovyClassLoader extends GroovyClassLoader {

    private static final String GENERATED_RULE_CLASS_PREFIX = "KairoRule_";

    private long artifactBytes;

    KairoGroovyClassLoader(ClassLoader parent, CompilerConfiguration configuration) {
        super(parent, configuration);
    }

    @Override
    protected ClassCollector createCollector(CompilationUnit unit, SourceUnit source) {
        return new ClassCollector(new InnerLoader(this), unit, source) {
            @Override
            protected Class<?> createClass(byte[] code, ClassNode classNode) {
                if (code != null) {
                    KairoGroovyClassLoader.this.artifactBytes += code.length;
                }
                return super.createClass(code, classNode);
            }
        };
    }

    /**
     * Kairo owns the namespace used for generated rule classes. A miss for one of these
     * names must therefore stay inside this Groovy loader instead of being delegated to
     * the target application's parent chain.
     *
     * <p>This is also a lifecycle requirement. Parallel-capable JDK ClassLoaders retain
     * one lock object for every distinct class name ever delegated to them. Dynamic rule
     * names (including Groovy helper classes and JavaBeans {@code BeanInfo}/{@code Customizer}
     * probes) would otherwise grow the long-lived application and platform loader maps even
     * after the rule, script loader, and business loader had all been reclaimed.
     */
    @Override
    public Class<?> loadClass(String name, boolean lookupScriptFiles,
                              boolean preferClassOverScript, boolean resolve)
            throws ClassNotFoundException, CompilationFailedException {
        if (name.startsWith(GENERATED_RULE_CLASS_PREFIX)) {
            Class<?> generated = getClassCacheEntry(name);
            if (generated != null) {
                return generated;
            }
            throw new ClassNotFoundException(name);
        }
        return super.loadClass(name, lookupScriptFiles, preferClassOverScript, resolve);
    }

    /** Return the bytecode bytes defined during the most recent parseClass and reset. */
    int consumeArtifactBytes() {
        int bytes = (int) Math.min(artifactBytes, Integer.MAX_VALUE);
        artifactBytes = 0L;
        return bytes;
    }
}
