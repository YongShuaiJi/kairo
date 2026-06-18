package com.example.runtimemock.groovy;

import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import org.codehaus.groovy.classgen.GeneratorContext;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class GroovyStructureCustomizer extends CompilationCustomizer {

    private static final int MAX_STATEMENTS = 10_000;
    private static final int MAX_CLOSURE_DEPTH = 5;
    private final Set<SourceUnit> checkedSources = ConcurrentHashMap.newKeySet();

    GroovyStructureCustomizer() {
        super(CompilePhase.SEMANTIC_ANALYSIS);
    }

    @Override
    public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
        if (!checkedSources.add(source)) {
            return;
        }
        StructureVisitor visitor = new StructureVisitor(source);
        source.getAST().getStatementBlock().visit(visitor);
    }

    private static final class StructureVisitor extends ClassCodeVisitorSupport {

        private final SourceUnit source;
        private int statementCount;
        private int closureDepth;

        private StructureVisitor(SourceUnit source) {
            this.source = source;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return source;
        }

        @Override
        public void visitClosureExpression(ClosureExpression expression) {
            closureDepth++;
            if (closureDepth > MAX_CLOSURE_DEPTH) {
                throw new SecurityException("Groovy closure nesting exceeds " + MAX_CLOSURE_DEPTH);
            }
            try {
                super.visitClosureExpression(expression);
            } finally {
                closureDepth--;
            }
        }

        @Override
        protected void visitStatement(org.codehaus.groovy.ast.stmt.Statement statement) {
            statementCount++;
            if (statementCount > MAX_STATEMENTS) {
                throw new SecurityException("Groovy script exceeds " + MAX_STATEMENTS + " statements");
            }
            super.visitStatement(statement);
        }
    }
}
