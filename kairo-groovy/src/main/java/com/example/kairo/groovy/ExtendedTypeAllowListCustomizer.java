package com.example.kairo.groovy;

import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;

import java.util.List;
import java.util.Set;

/**
 * EXTENDED-tier type allow-list gate, installed alongside the SAFE sensitive deny-list
 * customizer. It closes the FQN direct-usage bypass that a deny-list alone leaves open:
 * a non-sensitive class that is <em>neither</em> on the SAFE baseline nor declared in
 * {@code allowedClasses}/{@code allowedPackages} must not compile, no matter which AST
 * shape references it &mdash; {@code new com.example.other.Thing()} should be rejected
 * exactly like {@code import com.example.other.Thing}.
 *
 * <p>The customizer independently walks the AST and gates <em>every author-written type
 * reference</em> it can reach: imports, class expressions, constructor calls, static
 * receivers, annotations, casts, declarations (fields, method returns, parameters,
 * closure parameters), generics type arguments, and array component types. Only explicit
 * references written by the script author are gated; implicit Groovy runtime types
 * (Closure, GString, dynamic/{@code def} placeholders, the script base class hierarchy)
 * are not treated as author-written references, so they are left to the deny-list
 * customizer that runs first.
 *
 * <p>The sensitive-capability floor (IO, network, reflection, threads, processes,
 * classloaders, {@code groovy.lang}, {@code javax}, {@code sun}, {@code com.sun}, ...)
 * is checked <em>first</em>, before the configured allow-list. A sensitive type is
 * therefore denied even if a too-broad package (e.g. {@code java}) were configured.
 *
 * <p>This customizer is intentionally independent of
 * {@link org.codehaus.groovy.control.customizers.SecureASTCustomizer}'s allow-list
 * machinery: it owns its own visitor and resolution rules so the coverage above is not
 * bounded by what SecureASTCustomizer chooses to inspect.
 */
final class ExtendedTypeAllowListCustomizer extends CompilationCustomizer {

    private final Set<String> allowedPackages;
    private final Set<String> allowedClasses;

    ExtendedTypeAllowListCustomizer(Set<String> allowedPackages, Set<String> allowedClasses) {
        super(CompilePhase.SEMANTIC_ANALYSIS);
        this.allowedPackages = allowedPackages;
        this.allowedClasses = allowedClasses;
    }

    @Override
    public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
        checkImports(source);
        AllowListVisitor visitor = new AllowListVisitor(source);

        // Class-level annotations (e.g. @Foo on the script). The script base class
        // hierarchy is deliberately not walked: it is framework-imposed, not authored.
        checkAnnotations(classNode.getAnnotations(), "annotation");

        for (FieldNode field : classNode.getFields()) {
            // Skip framework-generated fields (no source position).
            if (field.isSynthetic() || field.getLineNumber() < 0) {
                continue;
            }
            checkType(field.getType(), "field " + field.getName());
            checkAnnotations(field.getAnnotations(), "field annotation");
            Expression init = field.getInitialExpression();
            if (init != null) {
                init.visit(visitor);
            }
        }
        for (MethodNode method : classNode.getMethods()) {
            // Skip framework-generated methods. A script's implicit main()/run() carry no
            // source position and their code contains synthesized dispatch such as
            // InvokerHelper.runScript(ScriptClass); author-written methods always have one.
            if (method.isSynthetic() || method.isSyntheticPublic() || method.getLineNumber() < 0) {
                continue;
            }
            checkType(method.getReturnType(), "method return " + method.getName());
            checkAnnotations(method.getAnnotations(), "method annotation");
            for (Parameter parameter : method.getParameters()) {
                checkType(parameter.getType(), "parameter " + parameter.getName());
                checkAnnotations(parameter.getAnnotations(), "parameter annotation");
            }
            Statement code = method.getCode();
            if (code != null) {
                code.visit(visitor);
            }
        }

        // The authored script body lives in the module statement block at this phase.
        Statement statementBlock = source.getAST().getStatementBlock();
        if (statementBlock != null) {
            statementBlock.visit(visitor);
        }
    }

    private void checkImports(SourceUnit source) {
        for (ImportNode imp : source.getAST().getImports()) {
            assertAllowed(imp.getClassName(), "import");
        }
        // Static imports name a class member; the owning class is what must be permitted.
        for (ImportNode imp : source.getAST().getStaticImports().values()) {
            assertAllowed(imp.getClassName(), "static import");
        }
        for (ImportNode imp : source.getAST().getStarImports()) {
            assertAllowedPackage(packageOf(imp), "star import");
        }
        for (ImportNode imp : source.getAST().getStaticStarImports().values()) {
            assertAllowedPackage(packageOf(imp), "static star import");
        }
    }

    private static String packageOf(ImportNode imp) {
        String pkg = imp.getPackageName();
        if (pkg != null && !pkg.isBlank()) {
            return stripTrailingSeparator(pkg);
        }
        return stripTrailingSeparator(imp.getClassName());
    }

    /**
     * Check a resolved type reference, unwrapping arrays and recursing into generic
     * type arguments so that {@code List<com.example.other.Thing>} and
     * {@code com.example.other.Thing[]} are both gated on the element type.
     */
    private void checkType(ClassNode type, String context) {
        if (type == null) {
            return;
        }
        ClassNode current = type;
        while (current != null && current.isArray()) {
            current = current.getComponentType();
        }
        if (current == null) {
            return;
        }
        if (ClassHelper.isDynamicTyped(current) || current == ClassHelper.dynamicType()
                || current == ClassHelper.OBJECT_TYPE || ClassHelper.isPrimitiveType(current)) {
            checkGenerics(current, context);
            return;
        }
        String name = current.getName();
        if (name == null || name.isBlank()) {
            checkGenerics(current, context);
            return;
        }
        assertAllowed(name, context);
        checkGenerics(current, context);
    }

    private void checkGenerics(ClassNode type, String context) {
        GenericsType[] generics = type.getGenericsTypes();
        if (generics == null) {
            return;
        }
        for (GenericsType generic : generics) {
            if (generic == null) {
                continue;
            }
            checkType(generic.getType(), context);
            ClassNode[] upper = generic.getUpperBounds();
            if (upper != null) {
                for (ClassNode bound : upper) {
                    checkType(bound, context);
                }
            }
            checkType(generic.getLowerBound(), context);
        }
    }

    private void checkAnnotations(List<AnnotationNode> annotations, String context) {
        if (annotations == null || annotations.isEmpty()) {
            return;
        }
        for (AnnotationNode annotation : annotations) {
            if (annotation == null) {
                continue;
            }
            // Skip framework-added annotations such as the implicit
            // @groovy.transform.BaseScript that Groovy attaches to every script whose
            // base class is configured via CompilerConfiguration. Those carry no source
            // position; author-written annotations always do.
            if (annotation.getLineNumber() < 0) {
                continue;
            }
            checkType(annotation.getClassNode(), context);
        }
    }

    private void assertAllowed(String name, String context) {
        if (name == null || name.isBlank()) {
            return;
        }
        // Hard floor: sensitive types are denied even if a broad package is configured.
        if (GroovyScriptSecurityPolicy.isSensitiveType(name)) {
            throw new SecurityException(
                    "Forbidden EXTENDED " + context + " (sensitive type): " + name);
        }
        if (isAllowedClass(name)) {
            return;
        }
        throw new SecurityException(
                "Forbidden EXTENDED " + context + " (not in allow-list): " + name);
    }

    private void assertAllowedPackage(String pkg, String context) {
        if (pkg == null || pkg.isBlank()) {
            return;
        }
        if (GroovyScriptSecurityPolicy.isSensitiveType(pkg)) {
            throw new SecurityException(
                    "Forbidden EXTENDED " + context + " (sensitive package): " + pkg);
        }
        if (isAllowedPackage(pkg)) {
            return;
        }
        throw new SecurityException(
                "Forbidden EXTENDED " + context + " (not in allow-list): " + pkg);
    }

    private boolean isAllowedClass(String name) {
        if (allowedClasses.contains(name)) {
            return true;
        }
        for (String pkg : allowedPackages) {
            if (name.equals(pkg) || name.startsWith(pkg + ".")) {
                return true;
            }
        }
        // SAFE baseline: the standard library minus sensitive sub-packages (the floor
        // above already rejected sensitive names) and the Kairo script API.
        if (name.startsWith("java.")) {
            return true;
        }
        return name.startsWith("com.example.kairo.");
    }

    private boolean isAllowedPackage(String pkg) {
        for (String allowed : allowedPackages) {
            if (pkg.equals(allowed) || pkg.startsWith(allowed + ".")) {
                return true;
            }
        }
        if (pkg.equals("java") || pkg.startsWith("java.")) {
            return true;
        }
        return pkg.startsWith("com.example.kairo.");
    }

    private static String stripTrailingSeparator(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.endsWith(".*")) {
            t = t.substring(0, t.length() - 2);
        }
        if (t.endsWith(".")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /**
     * Visits author-written type references inside expressions. Method-call and
     * property receivers that are {@link ClassExpression}s are covered by
     * {@link #visitClassExpression} via the default recursion, so only the nodes whose
     * types are not otherwise visited need explicit handling here.
     */
    private final class AllowListVisitor extends ClassCodeVisitorSupport {

        private final SourceUnit source;

        private AllowListVisitor(SourceUnit source) {
            this.source = source;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return source;
        }

        @Override
        public void visitClassExpression(ClassExpression expression) {
            super.visitClassExpression(expression);
            checkType(expression.getType(), "class expression");
        }

        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression expression) {
            super.visitConstructorCallExpression(expression);
            checkType(expression.getType(), "constructor");
        }

        @Override
        public void visitStaticMethodCallExpression(StaticMethodCallExpression expression) {
            super.visitStaticMethodCallExpression(expression);
            checkType(expression.getOwnerType(), "static receiver");
        }

        @Override
        public void visitCastExpression(CastExpression expression) {
            super.visitCastExpression(expression);
            checkType(expression.getType(), "cast");
        }

        @Override
        public void visitClosureExpression(ClosureExpression expression) {
            super.visitClosureExpression(expression);
            for (Parameter parameter : expression.getParameters()) {
                if (parameter == null) {
                    continue;
                }
                checkType(parameter.getType(), "closure parameter " + parameter.getName());
                checkAnnotations(parameter.getAnnotations(), "closure parameter annotation");
            }
        }

        @Override
        public void visitDeclarationExpression(DeclarationExpression expression) {
            super.visitDeclarationExpression(expression);
            // Only single-variable declarations carry an authored type; tuple
            // destructuring has no authored element type to gate.
            if (expression.getLeftExpression() instanceof VariableExpression variable) {
                checkType(variable.getOriginType(), "declaration " + variable.getName());
            }
        }
    }
}
