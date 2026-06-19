package com.example.runtimemock.agent.core;

import com.example.runtimemock.api.MockRule;
import com.example.runtimemock.bridge.RuntimeMockBridge;
import com.example.runtimemock.core.AgentBridgeDispatcher;
import com.example.runtimemock.core.ClassLoaderIdentity;
import com.example.runtimemock.core.CompiledRule;
import com.example.runtimemock.core.MethodDescriptor;
import com.example.runtimemock.core.MethodKey;
import com.example.runtimemock.core.RuleDispatcher;
import com.example.runtimemock.core.RulePublisher;
import com.example.runtimemock.core.RuleRegistry;
import com.example.runtimemock.core.RuleSet;
import com.example.runtimemock.groovy.CompiledMockScript;
import com.example.runtimemock.groovy.GroovyScriptCompiler;
import com.example.runtimemock.object.DefaultRuntimeObjectFactory;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class AgentRuntime implements AutoCloseable {

    private final Instrumentation instrumentation;
    private final DefaultInstrumentationRegistry instrumentationRegistry;
    private final RuleRegistry ruleRegistry;
    private final GroovyScriptCompiler scriptCompiler;
    private final RulePublisher rulePublisher;
    private final RuleDispatcher ruleDispatcher;
    private final RecordingInvocationObserver recordingObserver;
    private final ByteBuddyTransformerManager transformerManager;
    private final LoadedClassRepository loadedClassRepository;
    private final RuntimeEventBuffer eventBuffer;
    private final ConcurrentHashMap<String, PublishedRule> publishedRules = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RecordingRegistration> activeRecordings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> degradedClasses = new ConcurrentHashMap<>();
    private final AtomicReference<AgentState> state = new AtomicReference<>(AgentState.STARTING);
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "runtime-mock-rule-cleanup");
        thread.setDaemon(true);
        return thread;
    });
    private final long startTimeMillis = System.currentTimeMillis();
    private volatile boolean globallyEnabled = true;
    private volatile String loadMode = "unknown";

    public AgentRuntime(Instrumentation instrumentation) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.instrumentationRegistry = new DefaultInstrumentationRegistry();
        this.ruleRegistry = new RuleRegistry();
        this.scriptCompiler = new GroovyScriptCompiler(AgentRuntime.class.getClassLoader());
        this.rulePublisher = new RulePublisher(scriptCompiler, ruleRegistry);
        this.eventBuffer = new RuntimeEventBuffer();
        this.ruleDispatcher = new RuleDispatcher(ruleRegistry, new DefaultRuntimeObjectFactory(),
                new com.example.runtimemock.core.DecisionValidator(),
                new com.example.runtimemock.core.ReentryGuard(),
                new com.example.runtimemock.core.SamplingPolicy(),
                eventBuffer,
                java.time.Clock.systemUTC());
        this.recordingObserver = new RecordingInvocationObserver();
        this.transformerManager = new ByteBuddyTransformerManager(instrumentation, instrumentationRegistry);
        this.loadedClassRepository = new LoadedClassRepository(instrumentation);
    }

    public void start() {
        try {
            RuntimeMockBridge.install(new AgentBridgeDispatcher(ruleDispatcher, recordingObserver));
            transformerManager.install();
            cleanupExecutor.scheduleWithFixedDelay(this::cleanupExpiredRules, 1, 1, TimeUnit.SECONDS);
            state.set(AgentState.ACTIVE);
            eventBuffer.record("agent.start", "system", null, null, "Runtime mock agent started");
        } catch (RuntimeException e) {
            enterDegraded("Agent startup failed: " + e.getMessage());
            throw e;
        }
    }

    public void loadMode(String loadMode) {
        this.loadMode = loadMode == null ? "unknown" : loadMode;
    }

    public CompiledRule publish(Method method, MockRule rule) {
        return publish(method, rule, "system");
    }

    public CompiledRule publish(Method method, MockRule rule, String actor) {
        requireOperationalForPublish();
        if (method.isSynthetic() || method.isBridge()) {
            throw new IllegalArgumentException("Synthetic and bridge methods cannot be mocked: " + method);
        }
        MethodSignature signature = signatureOf(method);
        MethodKey methodKey = MethodKey.of(method);
        RuleSet oldRuleSet = ruleRegistry.rules(methodKey);
        PublishedRule previous = publishedRules.get(rule.id());
        boolean oldActive = shouldInstrument(methodKey, oldRuleSet);
        boolean publishApplied = false;
        try {
            CompiledRule compiledRule = rulePublisher.publish(method, rule);
            publishApplied = true;
            publishedRules.put(rule.id(), new PublishedRule(method, methodKey, compiledRule.rule(), compiledRule));
            applyInstrumentationTransition(method, signature, oldActive,
                    shouldInstrument(methodKey, ruleRegistry.rules(methodKey)));
            eventBuffer.record(previous == null ? "rule.create" : "rule.update", actor, rule.id(),
                    methodKey.toString(), "Published rule version " + compiledRule.rule().version());
            return compiledRule;
        } catch (RuntimeException e) {
            if (publishApplied) {
                ruleRegistry.restoreRule(methodKey, rule.id(),
                        previous == null ? null : previous.compiledRule());
                if (previous == null) {
                    publishedRules.remove(rule.id());
                } else {
                    publishedRules.put(rule.id(), previous);
                }
                try {
                    restoreInstrumentationState(method, signature, oldActive);
                } catch (RuntimeException restoreFailure) {
                    degradedClasses.put(method.getDeclaringClass().getName(), restoreFailure.getMessage());
                    enterDegraded("Cannot restore instrumentation for " + method.getDeclaringClass().getName());
                    e.addSuppressed(restoreFailure);
                }
            }
            eventBuffer.record("rule.publish.failed", actor, rule.id(), methodKey.toString(), e.getMessage());
            throw e;
        }
    }

    public CompiledRule publish(String classId, MockRule rule, String actor) {
        Method method = loadedClassRepository.resolveMethod(classId, rule.target().methodName(), rule.target().methodDescriptor());
        return publish(method, rule, actor);
    }

    public void remove(Method method, String ruleId) {
        remove(MethodKey.of(method), ruleId, "system");
    }

    public void remove(String ruleId, String actor) {
        PublishedRule publishedRule = requireRule(ruleId);
        remove(publishedRule.methodKey(), ruleId, actor);
    }

    public CompiledRule setEnabled(String ruleId, boolean enabled, String actor) {
        PublishedRule publishedRule = requireRule(ruleId);
        MockRule next = publishedRule.rule().toBuilder()
                .enabled(enabled)
                .version(publishedRule.rule().version() + 1)
                .build();
        CompiledRule compiledRule = publish(publishedRule.method(), next, actor);
        eventBuffer.record(enabled ? "rule.enable" : "rule.disable", actor, ruleId,
                publishedRule.methodKey().toString(), "Rule enabled=" + enabled);
        return compiledRule;
    }

    private void remove(MethodKey methodKey, String ruleId, String actor) {
        PublishedRule publishedRule = requireRule(ruleId);
        Method method = publishedRule.method();
        MethodSignature signature = signatureOf(method);
        RuleSet oldRuleSet = ruleRegistry.rules(methodKey);
        boolean oldActive = shouldInstrument(methodKey, oldRuleSet);
        try {
            rulePublisher.remove(method, ruleId);
            publishedRules.remove(ruleId);
            applyInstrumentationTransition(method, signature, oldActive,
                    shouldInstrument(methodKey, ruleRegistry.rules(methodKey)));
            eventBuffer.record("rule.delete", actor, ruleId, methodKey.toString(), "Deleted rule");
        } catch (RuntimeException e) {
            ruleRegistry.restoreRule(methodKey, ruleId, publishedRule.compiledRule());
            publishedRules.put(ruleId, publishedRule);
            restoreInstrumentationState(method, signature, oldActive);
            eventBuffer.record("rule.delete.failed", actor, ruleId, methodKey.toString(), e.getMessage());
            throw e;
        }
    }

    public void disableAll(boolean disabled) {
        globallyEnabled = !disabled;
        ruleDispatcher.enabled(globallyEnabled);
        if (state.get() != AgentState.DEGRADED) {
            state.set(disabled ? AgentState.DISABLED : AgentState.ACTIVE);
        }
        eventBuffer.record(disabled ? "agent.disable-all" : "agent.enable-all", "system", null, null,
                "Global enabled=" + globallyEnabled);
    }

    public void resetAll(String actor) {
        state.set(AgentState.RESETTING);
        RuntimeMockBridge.uninstall();
        ruleDispatcher.enabled(false);
        try {
            ruleRegistry.clear();
            publishedRules.clear();
            activeRecordings.clear();
            recordingObserver.clear();
            instrumentationRegistry.snapshot().forEach(instrumentationRegistry::unregister);
            transformerManager.close();
            transformerManager.install();
            RuntimeMockBridge.install(new AgentBridgeDispatcher(ruleDispatcher, recordingObserver));
            degradedClasses.clear();
            globallyEnabled = true;
            ruleDispatcher.enabled(true);
            state.set(AgentState.ACTIVE);
            eventBuffer.record("agent.reset-all", actor, null, null, "All rules removed and transformer reset");
        } catch (RuntimeException e) {
            enterDegraded("Reset all failed: " + e.getMessage());
            eventBuffer.record("agent.reset-all.failed", actor, null, null, e.getMessage());
            throw e;
        }
    }

    public ResetClassResult resetClass(String classId, String actor) {
        activeRecordings.values().stream()
                .filter(recording -> classId.equals(recording.classId()) || classId.equals(recording.className()))
                .map(RecordingRegistration::sessionId)
                .toList()
                .forEach(sessionId -> stopRecording(sessionId, actor));
        List<String> ruleIds = publishedRules.values().stream()
                .filter(rule -> matchesClass(rule, classId))
                .map(rule -> rule.rule().id())
                .toList();
        List<String> removed = new java.util.ArrayList<>();
        Map<String, String> failures = new java.util.LinkedHashMap<>();
        for (String ruleId : ruleIds) {
            try {
                remove(ruleId, actor);
                removed.add(ruleId);
            } catch (RuntimeException e) {
                failures.put(ruleId, String.valueOf(e.getMessage()));
            }
        }
        if (failures.isEmpty()) {
            degradedClasses.remove(classId);
        } else {
            degradedClasses.put(classId, failures.toString());
            enterDegraded("Class reset failed for " + classId);
        }
        eventBuffer.record("agent.reset-class", actor, null, classId,
                "Removed " + removed.size() + " rules; failures=" + failures.size());
        return new ResetClassResult(classId, List.copyOf(removed), Map.copyOf(failures), rules(),
                !failures.isEmpty());
    }

    public void recordEvent(String type, String actor, String ruleId, String target, String message) {
        eventBuffer.record(type, actor, ruleId, target, message);
    }

    public void recordingSink(RecordingEventSink sink) {
        recordingObserver.sink(sink);
    }

    public RecordingRegistration startRecording(String sessionId, String classIdOrName,
                                                String methodName, String methodDescriptor,
                                                String actor) {
        requireOperationalForPublish();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("recording sessionId is required");
        }
        RecordingRegistration existing = activeRecordings.get(sessionId);
        if (existing != null) {
            return existing;
        }
        Method method = loadedClassRepository.resolveMethodTarget(
                classIdOrName, methodName, methodDescriptor);
        if (method.isSynthetic() || method.isBridge()) {
            throw new IllegalArgumentException("Synthetic and bridge methods cannot be recorded: " + method);
        }
        MethodKey methodKey = MethodKey.of(method);
        MethodSignature signature = signatureOf(method);
        boolean oldActive = shouldInstrument(methodKey, ruleRegistry.rules(methodKey));
        RecordingRegistration registration = new RecordingRegistration(
                sessionId,
                loadedClassRepository.classId(method.getDeclaringClass()),
                method.getDeclaringClass().getName(),
                method.getName(),
                MethodDescriptor.of(method)
        );
        try {
            recordingObserver.start(methodKey, registration);
            activeRecordings.put(sessionId, registration);
            applyInstrumentationTransition(method, signature, oldActive, true);
            eventBuffer.record("recording.start", actor, null, methodKey.toString(),
                    "Recording session " + sessionId + " started");
            return registration;
        } catch (RuntimeException e) {
            activeRecordings.remove(sessionId);
            recordingObserver.stop(methodKey, sessionId);
            restoreInstrumentationState(method, signature, oldActive);
            eventBuffer.record("recording.start.failed", actor, null, methodKey.toString(), e.getMessage());
            throw e;
        }
    }

    public RecordingRegistration stopRecording(String sessionId, String actor) {
        RecordingRegistration registration = activeRecordings.remove(sessionId);
        if (registration == null) {
            return null;
        }
        Method method = loadedClassRepository.resolveMethod(
                registration.classId(), registration.methodName(), registration.methodDescriptor());
        MethodKey methodKey = MethodKey.of(method);
        MethodSignature signature = signatureOf(method);
        boolean oldActive = shouldInstrument(methodKey, ruleRegistry.rules(methodKey));
        recordingObserver.stop(methodKey, sessionId);
        boolean newActive = shouldInstrument(methodKey, ruleRegistry.rules(methodKey));
        try {
            applyInstrumentationTransition(method, signature, oldActive, newActive);
            eventBuffer.record("recording.stop", actor, null, methodKey.toString(),
                    "Recording session " + sessionId + " stopped");
            return registration;
        } catch (RuntimeException e) {
            recordingObserver.start(methodKey, registration);
            activeRecordings.put(sessionId, registration);
            restoreInstrumentationState(method, signature, oldActive);
            eventBuffer.record("recording.stop.failed", actor, null, methodKey.toString(), e.getMessage());
            throw e;
        }
    }

    public List<RecordingRegistration> recordings() {
        return activeRecordings.values().stream()
                .sorted(Comparator.comparing(RecordingRegistration::sessionId))
                .toList();
    }

    public CompiledMockScript compileScript(String ruleId, long version, String script) {
        CompiledMockScript compiled = scriptCompiler.compile(ruleId, version, script);
        eventBuffer.record("script.compile", "api", ruleId, null, "Compiled script " + compiled.scriptHash());
        return compiled;
    }

    public List<ClassInfo> searchClasses(String keyword, int limit) {
        return loadedClassRepository.search(keyword, limit);
    }

    public List<MethodInfo> methods(String classId) {
        return loadedClassRepository.methods(classId);
    }

    public LoadedClassRepository loadedClassRepository() {
        return loadedClassRepository;
    }

    public List<RuleInfo> rules() {
        return publishedRules.values().stream()
                .sorted(Comparator.comparing(rule -> rule.rule().id()))
                .map(this::toRuleInfo)
                .toList();
    }

    public List<RuntimeEvent> events() {
        return eventBuffer.snapshot();
    }

    public RuntimeMetrics metrics() {
        int totalRuleCount = publishedRules.size();
        int activeRuleCount = (int) publishedRules.values().stream()
                .filter(rule -> isActive(rule.compiledRule().rule()))
                .count();
        long hits = publishedRules.values().stream().mapToLong(rule -> rule.compiledRule().hits()).sum();
        long errors = publishedRules.values().stream().mapToLong(rule -> rule.compiledRule().errors()).sum();
        return new RuntimeMetrics(
                instrumentation.getAllLoadedClasses().length,
                instrumentationRegistry.typeCount(),
                instrumentationRegistry.methodCount(),
                totalRuleCount,
                activeRuleCount,
                hits,
                errors,
                globallyEnabled
        );
    }

    public JvmInfo jvmInfo() {
        RuntimeMetrics metrics = metrics();
        return new JvmInfo(
                ManagementFactory.getRuntimeMXBean().getName(),
                ProcessHandle.current().pid(),
                hostName(),
                System.getProperty("java.version"),
                startTimeMillis,
                "0.1.0-SNAPSHOT",
                loadMode,
                state.get().name(),
                metrics.enhancedClassCount(),
                metrics.enhancedMethodCount(),
                metrics.activeRuleCount()
        );
    }

    public RuleRegistry ruleRegistry() {
        return ruleRegistry;
    }

    public DefaultInstrumentationRegistry instrumentationRegistry() {
        return instrumentationRegistry;
    }

    public ByteBuddyTransformerManager transformerManager() {
        return transformerManager;
    }

    @Override
    public void close() {
        state.set(AgentState.STOPPING);
        RuntimeMockBridge.uninstall();
        ruleRegistry.clear();
        publishedRules.clear();
        activeRecordings.clear();
        recordingObserver.clear();
        transformerManager.close();
        scriptCompiler.close();
        cleanupExecutor.shutdownNow();
        state.set(AgentState.STOPPED);
        eventBuffer.record("agent.stop", "system", null, null, "Runtime mock agent stopped");
    }

    private MethodSignature signatureOf(Method method) {
        return new MethodSignature(
                method.getDeclaringClass().getName(),
                ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()),
                method.getName(),
                MethodDescriptor.of(method)
        );
    }

    private void applyInstrumentationTransition(Method method, MethodSignature signature,
                                                boolean oldActive, boolean newActive) {
        if (!oldActive && newActive) {
            instrumentationRegistry.register(signature);
            transformerManager.retransform(method.getDeclaringClass());
        } else if (oldActive && !newActive) {
            instrumentationRegistry.unregister(signature);
            transformerManager.retransform(method.getDeclaringClass());
        }
    }

    private void restoreInstrumentationState(Method method, MethodSignature signature, boolean shouldBeActive) {
        if (shouldBeActive) {
            instrumentationRegistry.register(signature);
        } else {
            instrumentationRegistry.unregister(signature);
        }
        transformerManager.retransform(method.getDeclaringClass());
    }

    private boolean hasActiveRules(RuleSet ruleSet) {
        return ruleSet.all().stream().anyMatch(rule -> isActive(rule.rule()));
    }

    private boolean shouldInstrument(MethodKey methodKey, RuleSet ruleSet) {
        return hasActiveRules(ruleSet) || recordingObserver.isRecording(methodKey);
    }

    private boolean isActive(MockRule rule) {
        return rule.enabled()
                && rule.percentage() > 0
                && (rule.expireAt() <= 0 || rule.expireAt() > System.currentTimeMillis());
    }

    private PublishedRule requireRule(String ruleId) {
        PublishedRule publishedRule = publishedRules.get(ruleId);
        if (publishedRule == null) {
            throw new IllegalArgumentException("Rule not found: " + ruleId);
        }
        return publishedRule;
    }

    private boolean matchesClass(PublishedRule publishedRule, String classIdOrName) {
        if (classIdOrName == null || classIdOrName.isBlank()) {
            throw new IllegalArgumentException("classId is required");
        }
        MethodKey methodKey = publishedRule.methodKey();
        String resolvedClassId = loadedClassRepository.classId(methodKey.className(), methodKey.classLoaderId());
        return classIdOrName.equals(resolvedClassId) || classIdOrName.equals(methodKey.className());
    }

    private void cleanupExpiredRules() {
        if (state.get() == AgentState.DEGRADED || state.get() == AgentState.STOPPING
                || state.get() == AgentState.STOPPED) {
            return;
        }
        long now = System.currentTimeMillis();
        publishedRules.values().stream()
                .filter(rule -> rule.rule().expireAt() > 0 && rule.rule().expireAt() <= now)
                .map(rule -> rule.rule().id())
                .toList()
                .forEach(ruleId -> {
                    try {
                        remove(ruleId, "ttl-cleanup");
                    } catch (RuntimeException e) {
                        eventBuffer.record("rule.cleanup.failed", "ttl-cleanup", ruleId, null, e.getMessage());
                    }
                });
    }

    private RuleInfo toRuleInfo(PublishedRule publishedRule) {
        MockRule rule = publishedRule.rule();
        MethodKey methodKey = publishedRule.methodKey();
        return new RuleInfo(
                rule.id(),
                rule.version(),
                rule.name(),
                rule.description(),
                loadedClassRepository.classId(methodKey.className(), methodKey.classLoaderId()),
                methodKey.className(),
                methodKey.classLoaderId(),
                methodKey.methodName(),
                methodKey.methodDescriptor(),
                rule.phase(),
                rule.priority(),
                rule.percentage(),
                rule.maxHits(),
                rule.expireAt(),
                rule.failOpen(),
                rule.enabled(),
                publishedRule.compiledRule().hits(),
                publishedRule.compiledRule().errors(),
                publishedRule.compiledRule().locked(),
                rule.scriptHash()
        );
    }

    private String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "localhost";
        }
    }

    private void requireOperationalForPublish() {
        AgentState current = state.get();
        if (current != AgentState.ACTIVE && current != AgentState.DISABLED) {
            throw new IllegalStateException("Agent does not accept rule publication while state=" + current);
        }
    }

    private void enterDegraded(String message) {
        globallyEnabled = false;
        ruleDispatcher.enabled(false);
        RuntimeMockBridge.uninstall();
        state.set(AgentState.DEGRADED);
        eventBuffer.record("agent.degraded", "system", null, null, message);
    }
}
