package com.example.kairo.groovy;

import org.codehaus.groovy.ast.ClassCodeExpressionTransformer;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;

/**
 * Preserves Kairo's script surface when using Groovy's unload-safe classic call sites.
 */
final class ClassicCallSiteCompatibilityCustomizer extends CompilationCustomizer {

    ClassicCallSiteCompatibilityCustomizer() {
        super(CompilePhase.INSTRUCTION_SELECTION);
    }

    @Override
    public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
        classNode.visitContents(new ClassCodeExpressionTransformer() {
            @Override
            protected SourceUnit getSourceUnit() {
                return source;
            }

            @Override
            public Expression transform(Expression expression) {
                Expression transformed = super.transform(expression);
                if (!(transformed instanceof MethodCallExpression call)
                        || !"clone".equals(call.getMethodAsString())
                        || !(call.getObjectExpression() instanceof VariableExpression variable)
                        || !"args".equals(variable.getName())
                        || !(call.getArguments() instanceof TupleExpression arguments)
                        || !arguments.getExpressions().isEmpty()) {
                    return transformed;
                }
                StaticMethodCallExpression replacement = new StaticMethodCallExpression(
                        ClassHelper.make(ClassicCallSiteSupport.class),
                        "cloneArguments",
                        new ArgumentListExpression(call.getObjectExpression()));
                replacement.setSourcePosition(call);
                return replacement;
            }
        });
    }
}
