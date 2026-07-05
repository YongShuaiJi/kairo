package com.example.kairo.platform.service;

import com.example.kairo.api.InvocationContext;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MethodMetadata;
import com.example.kairo.api.MockApi;
import com.example.kairo.api.MockDecision;
import com.example.kairo.api.ScriptLog;
import com.example.kairo.groovy.CompiledMockScript;
import com.example.kairo.groovy.GroovyScriptCompiler;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public final class ScriptWorkbenchService {

    private static final Pattern LINE_PATTERN = Pattern.compile("@ line (\\d+), column (\\d+)");
    private static final Method PROBE_METHOD;

    static {
        try {
            PROBE_METHOD = ScriptWorkbenchService.class.getDeclaredMethod("probe", Object[].class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final GroovyScriptCompiler compiler = new GroovyScriptCompiler(
            ScriptWorkbenchService.class.getClassLoader());

    public Map<String, Object> validate(Map<String, Object> request) {
        String script = script(request);
        long started = System.nanoTime();
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        try {
            compiler.compile("workbench-" + UUID.randomUUID(), 1, script);
        } catch (RuntimeException e) {
            diagnostics.add(diagnostic(e));
        }
        return Map.of(
                "valid", diagnostics.isEmpty(),
                "diagnostics", diagnostics,
                "compileTimeMs", Duration.ofNanos(System.nanoTime() - started).toMillis(),
                "policy", "kairo-groovy-secure-ast"
        );
    }

    public Map<String, Object> test(Map<String, Object> request) {
        String script = script(request);
        Map<String, Object> input = map(request.get("input"));
        Object[] arguments = list(input.get("args")).toArray();
        Object originalResult = input.get("result");
        InvokePhase phase = phase(input.get("phase"));
        Throwable throwable = throwable(input.get("throwable"));
        long started = System.nanoTime();
        RecordingLog log = new RecordingLog();
        try {
            CompiledMockScript compiled = compiler.compile("workbench-" + UUID.randomUUID(), 1, script);
            MockDecision decision;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                decision = executor.submit(() -> compiled.execute(
                                new WorkbenchInvocationContext(phase, arguments, originalResult, throwable, log)))
                        .get(1, TimeUnit.SECONDS);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "SUCCESS");
            result.put("durationMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
            result.put("output", decisionOutput(decision, originalResult));
            result.put("decision", decision.type().name());
            result.put("logs", log.entries());
            result.put("diff", Map.of("before", originalResult == null ? Map.of() : originalResult,
                    "after", decisionOutput(decision, originalResult)));
            result.put("limits", Map.of("timeoutMs", 1000, "policy", "secure-ast"));
            return result;
        } catch (Exception e) {
            Throwable cause = root(e);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "FAILED");
            result.put("durationMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
            result.put("exception", Map.of(
                    "type", cause.getClass().getName(),
                    "message", cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
            result.put("logs", log.entries());
            result.put("limits", Map.of("timeoutMs", 1000, "policy", "secure-ast"));
            return result;
        }
    }

    @PreDestroy
    void close() {
        compiler.close();
    }

    private Map<String, Object> diagnostic(RuntimeException exception) {
        Throwable cause = root(exception);
        String message = cause.getMessage() == null ? exception.getMessage() : cause.getMessage();
        Matcher matcher = LINE_PATTERN.matcher(message == null ? "" : message);
        int line = matcher.find() ? Integer.parseInt(matcher.group(1)) : 1;
        int column = matcher.find(0) ? Integer.parseInt(matcher.group(2)) : 1;
        return Map.of(
                "severity", "error",
                "code", message != null && message.toLowerCase().contains("forbidden")
                        ? "FORBIDDEN_SCRIPT" : "SCRIPT_COMPILE_ERROR",
                "message", message == null ? "Script validation failed" : message,
                "line", line,
                "column", column
        );
    }

    private Object decisionOutput(MockDecision decision, Object originalResult) {
        return switch (decision.type()) {
            case PROCEED -> decision.hasArguments()
                    ? Map.of("arguments", List.of(decision.arguments()))
                    : originalResult == null ? Map.of("proceed", true) : originalResult;
            case RETURN -> decision.returnValue() == null ? Map.of() : decision.returnValue();
            case THROW -> Map.of("throwable", decision.throwable().getClass().getName(),
                    "message", String.valueOf(decision.throwable().getMessage()));
        };
    }

    private String script(Map<String, Object> request) {
        Object value = request.get("script");
        if (value instanceof Map<?, ?> scriptMap) {
            value = scriptMap.get("script");
        }
        if (value == null || String.valueOf(value).isBlank()) {
            throw PlatformException.badRequest("FIELD_REQUIRED", "Missing required field: script");
        }
        return String.valueOf(value);
    }

    private InvokePhase phase(Object value) {
        try {
            return value == null ? InvokePhase.RETURN : InvokePhase.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw PlatformException.badRequest("INVALID_PHASE", "phase must be BEFORE, RETURN, or THROWS");
        }
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<?> list(Object value) {
        return value instanceof List<?> values ? values : List.of();
    }

    private Throwable throwable(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> source) {
            Object type = source.get("type");
            Object message = source.get("message");
            return new IllegalStateException((type == null ? "java.lang.IllegalStateException" : type)
                    + ": " + (message == null ? "Workbench exception" : message));
        }
        return new IllegalStateException(String.valueOf(value));
    }

    private Throwable root(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor;
    }

    private static Object probe(Object[] arguments) {
        return arguments;
    }

    private static final class WorkbenchInvocationContext implements InvocationContext {
        private final InvokePhase phase;
        private final Object[] arguments;
        private final Object result;
        private final Throwable throwable;
        private final RecordingLog log;
        private final MockApi api = new WorkbenchMockApi();

        private WorkbenchInvocationContext(InvokePhase phase, Object[] arguments, Object result,
                                           Throwable throwable, RecordingLog log) {
            this.phase = phase;
            this.arguments = arguments;
            this.result = result;
            this.throwable = throwable;
            this.log = log;
        }

        public InvokePhase phase() { return phase; }
        public Object[] arguments() { return arguments; }
        public Object target() { return null; }
        public Object result() { return result; }
        public Throwable throwable() { return throwable; }
        public MethodMetadata method() { return new MethodMetadata(PROBE_METHOD, "([Ljava/lang/Object;)Ljava/lang/Object;"); }
        public MockApi mockApi() { return api; }
        public ScriptLog log() { return log; }
    }

    private static final class WorkbenchMockApi implements MockApi {
        public MockDecision proceed() { return MockDecision.proceed(); }
        public MockDecision proceed(Object[] arguments) { return MockDecision.proceed(arguments); }
        public MockDecision returnValue(Object value) { return MockDecision.returnValue(value); }
        public MockDecision returnJson(String json) { return MockDecision.returnValue(json); }
        public MockDecision throwException(Throwable throwable) { return MockDecision.throwException(throwable); }
        public MockDecision throwException(String exceptionClassName, String message) {
            return MockDecision.throwException(new IllegalStateException(exceptionClassName + ": " + message));
        }
        public Object newReturnObject() { return new LinkedHashMap<>(); }
        public Object fromJson(String json, Class<?> targetType) { return json; }
        public Object get(Object target, String propertyPath) { return null; }
        public void set(Object target, String propertyPath, Object value) { }
        public boolean isType(Object target, String className) {
            return target != null && target.getClass().getName().equals(className);
        }
    }

    private static final class RecordingLog implements ScriptLog {
        private final List<String> entries = new ArrayList<>();
        public void debug(String message) { add("DEBUG", message); }
        public void info(String message) { add("INFO", message); }
        public void warn(String message) { add("WARN", message); }
        public void error(String message, Throwable throwable) {
            add("ERROR", message + (throwable == null ? "" : ": " + throwable.getMessage()));
        }
        private synchronized void add(String level, String message) {
            if (entries.size() < 100) {
                entries.add(level + " " + String.valueOf(message).substring(
                        0, Math.min(500, String.valueOf(message).length())));
            }
        }
        private synchronized List<String> entries() { return List.copyOf(entries); }
    }
}
