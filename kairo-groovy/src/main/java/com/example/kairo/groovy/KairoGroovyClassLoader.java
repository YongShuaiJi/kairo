package com.example.kairo.groovy;

import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.ast.ClassNode;
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

    /** Return the bytecode bytes defined during the most recent parseClass and reset. */
    int consumeArtifactBytes() {
        int bytes = (int) Math.min(artifactBytes, Integer.MAX_VALUE);
        artifactBytes = 0L;
        return bytes;
    }
}
