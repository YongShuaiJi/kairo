package com.example.runtimemock.groovy;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.SynchronizedStatement;
import org.codehaus.groovy.ast.stmt.WhileStatement;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class GroovyScriptSecurityPolicy {

    private static final int MAX_SCRIPT_CHARS = 16 * 1024;
    private static final int MAX_SCRIPT_LINES = 400;
    private static final int MAX_CLOSURE_CHARS = 8 * 1024;

    private static final List<String> FORBIDDEN_SOURCE_MARKERS = List.of(
            "@Grab",
            "@Grapes",
            "groovy.grape.Grape",
            "package "
    );

    private static final List<String> DISALLOWED_IMPORTS = List.of(
            "java.io.File",
            "java.lang.Class",
            "java.lang.ClassLoader",
            "java.lang.ProcessBuilder",
            "java.lang.Runtime",
            "java.lang.System",
            "java.lang.Thread",
            "java.lang.reflect.Constructor",
            "java.lang.reflect.Field",
            "java.lang.reflect.Method",
            "java.net.ServerSocket",
            "java.net.Socket",
            "java.net.URI",
            "java.net.URL",
            "java.nio.file.Files",
            "java.nio.file.Path",
            "java.nio.file.Paths",
            "java.util.concurrent.ExecutorService",
            "java.util.concurrent.Executors"
    );

    private static final List<String> DISALLOWED_STAR_IMPORTS = List.of(
            "java.io.",
            "java.lang.reflect.",
            "java.net.",
            "java.nio.",
            "java.util.concurrent.",
            "javax.",
            "sun.",
            "com.sun."
    );

    @SuppressWarnings("rawtypes")
    private static final List<Class> DISALLOWED_RECEIVER_CLASSES = List.of(
            Class.class,
            ClassLoader.class,
            Constructor.class,
            ExecutorService.class,
            Executors.class,
            Field.class,
            File.class,
            Files.class,
            Method.class,
            Path.class,
            Paths.class,
            ProcessBuilder.class,
            Runtime.class,
            ServerSocket.class,
            Socket.class,
            System.class,
            Thread.class,
            URI.class,
            URL.class
    );

    private static final Set<String> DISALLOWED_METHODS = Set.of(
            "addShutdownHook",
            "defineClass",
            "exec",
            "exit",
            "forName",
            "getClass",
            "getClassLoader",
            "getDeclaredConstructor",
            "getDeclaredConstructors",
            "getDeclaredField",
            "getDeclaredFields",
            "getDeclaredMethod",
            "getDeclaredMethods",
            "getResource",
            "getResourceAsStream",
            "getRuntime",
            "invoke",
            "load",
            "loadClass",
            "newInstance",
            "notify",
            "notifyAll",
            "setAccessible",
            "sleep",
            "start",
            "stop",
            "wait"
    );

    private static final Set<String> DISALLOWED_PROPERTIES = Set.of(
            "class",
            "classLoader",
            "metaClass"
    );

    private GroovyScriptSecurityPolicy() {
    }

    static void validateSource(String script) {
        if (script.length() > MAX_SCRIPT_CHARS) {
            throw new IllegalArgumentException("Groovy script is too large: " + script.length()
                    + " characters, max " + MAX_SCRIPT_CHARS);
        }
        long lines = script.lines().count();
        if (lines > MAX_SCRIPT_LINES) {
            throw new IllegalArgumentException("Groovy script has too many lines: " + lines
                    + ", max " + MAX_SCRIPT_LINES);
        }
        for (String forbiddenMarker : FORBIDDEN_SOURCE_MARKERS) {
            if (script.contains(forbiddenMarker)) {
                throw new IllegalArgumentException("Forbidden Groovy source marker: " + forbiddenMarker);
            }
        }
    }

    static SecureASTCustomizer secureAstCustomizer() {
        SecureASTCustomizer customizer = new SecureASTCustomizer();
        customizer.setPackageAllowed(false);
        customizer.setMethodDefinitionAllowed(false);
        customizer.setClosuresAllowed(true);
        customizer.setIndirectImportCheckEnabled(true);
        customizer.setDisallowedImports(DISALLOWED_IMPORTS);
        customizer.setDisallowedStaticImports(DISALLOWED_IMPORTS);
        customizer.setDisallowedStarImports(DISALLOWED_STAR_IMPORTS);
        customizer.setDisallowedStaticStarImports(DISALLOWED_STAR_IMPORTS);
        customizer.setDisallowedReceiversClasses(DISALLOWED_RECEIVER_CLASSES);
        customizer.setDisallowedStatements(List.of(
                DoWhileStatement.class,
                ForStatement.class,
                SynchronizedStatement.class,
                WhileStatement.class
        ));
        customizer.addExpressionCheckers(GroovyScriptSecurityPolicy::isExpressionAuthorized);
        customizer.addStatementCheckers(GroovyScriptSecurityPolicy::isStatementAuthorized);
        return customizer;
    }

    private static boolean isExpressionAuthorized(Expression expression) {
        if (expression instanceof ConstructorCallExpression constructorCallExpression) {
            return !isDangerousType(constructorCallExpression.getType());
        }
        if (expression instanceof StaticMethodCallExpression staticMethodCallExpression) {
            return !isDangerousType(staticMethodCallExpression.getOwnerType())
                    && isMethodAllowed(staticMethodCallExpression.getMethod());
        }
        if (expression instanceof MethodCallExpression methodCallExpression) {
            return isMethodAllowed(methodCallExpression.getMethodAsString())
                    && isReceiverAllowed(methodCallExpression.getObjectExpression());
        }
        if (expression instanceof PropertyExpression propertyExpression) {
            return isPropertyAllowed(propertyExpression.getPropertyAsString())
                    && isReceiverAllowed(propertyExpression.getObjectExpression());
        }
        if (expression instanceof ClosureExpression closureExpression) {
            Statement code = closureExpression.getCode();
            return code == null || code.getText().length() <= MAX_CLOSURE_CHARS;
        }
        return true;
    }

    private static boolean isStatementAuthorized(Statement statement) {
        String text = statement == null ? "" : statement.getText();
        return text.length() <= MAX_CLOSURE_CHARS;
    }

    private static boolean isMethodAllowed(String methodName) {
        return methodName == null || !DISALLOWED_METHODS.contains(methodName);
    }

    private static boolean isPropertyAllowed(String propertyName) {
        return propertyName == null || !DISALLOWED_PROPERTIES.contains(propertyName);
    }

    private static boolean isReceiverAllowed(Expression expression) {
        if (expression == null) {
            return true;
        }
        if (expression instanceof ClassExpression classExpression) {
            return !isDangerousType(classExpression.getType());
        }
        if (expression instanceof VariableExpression variableExpression) {
            String variableName = variableExpression.getName();
            return !isDangerousName(variableName);
        }
        ClassNode type = expression.getType();
        return !isDangerousType(type);
    }

    private static boolean isDangerousType(ClassNode type) {
        if (type == null || type == ClassHelper.dynamicType()) {
            return false;
        }
        String name = type.getName();
        if (name == null) {
            return false;
        }
        if (isDangerousName(name)) {
            return true;
        }
        for (Class<?> forbiddenClass : DISALLOWED_RECEIVER_CLASSES) {
            if (forbiddenClass.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDangerousName(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.startsWith("java.io.")
                || normalized.startsWith("java.lang.reflect.")
                || normalized.startsWith("java.net.")
                || normalized.startsWith("java.nio.")
                || normalized.startsWith("java.util.concurrent.")
                || normalized.startsWith("javax.")
                || normalized.startsWith("sun.")
                || normalized.startsWith("com.sun.")
                || normalized.equals("class")
                || normalized.equals("classloader")
                || normalized.equals("metaclass")
                || normalized.equals("processbuilder")
                || normalized.equals("runtime")
                || normalized.equals("system")
                || normalized.equals("thread");
    }
}
