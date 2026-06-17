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

public final class AgentRuntime implements AutoCloseable {

    private final Instrumentation instrumentation;
    private final DefaultInstrumentationRegistry instrumentationRegistry;
    private final RuleRegistry ruleRegistry;
    private final GroovyScriptCompiler scriptCompiler;
    private final RulePublisher rulePublisher;
    private final RuleDispatcher ruleDispatcher;
    private final ByteBuddyTransformerManager transformerManager;
    private final LoadedClassRepository loadedClassRepository;
    private final RuntimeEventBuffer eventBuffer;
    private final ConcurrentHashMap<String, PublishedRule> publishedRules = new ConcurrentHashMap<>();
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
        this.transformerManager = new ByteBuddyTransformerManager(instrumentation, instrumentationRegistry);
        this.loadedClassRepository = new LoadedClassRepository(instrumentation);
    }

    public void start() {
        RuntimeMockBridge.install(new AgentBridgeDispatcher(ruleDispatcher));
        transformerManager.install();
        cleanupExecutor.scheduleWithFixedDelay(this::cleanupExpiredRules, 1, 1, TimeUnit.SECONDS);
        eventBuffer.record("agent.start", "system", null, null, "Runtime mock agent started");
    }

    public void loadMode(String loadMode) {
        this.loadMode = loadMode == null ? "unknown" : loadMode;
    }

    public CompiledRule publish(Method method, MockRule rule) {
        return publish(method, rule, "system");
    }

    public CompiledRule publish(Method method, MockRule rule, String actor) {
        MethodSignature signature = signatureOf(method);
        MethodKey methodKey = MethodKey.of(method);
        RuleSet oldRuleSet = ruleRegistry.rules(methodKey);
        PublishedRule previous = publishedRules.get(rule.id());
        boolean oldActive = hasActiveRules(oldRuleSet);
        try {
            CompiledRule compiledRule = rulePublisher.publish(method, rule);
            publishedRules.put(rule.id(), new PublishedRule(method, methodKey, compiledRule.rule(), compiledRule));
            applyInstrumentationTransition(method, signature, oldActive, hasActiveRules(ruleRegistry.rules(methodKey)));
            eventBuffer.record(previous == null ? "rule.create" : "rule.update", actor, rule.id(),
                    methodKey.toString(), "Published rule version " + compiledRule.rule().version());
            return compiledRule;
        } catch (RuntimeException e) {
            ruleRegistry.replace(methodKey, oldRuleSet);
            if (previous == null) {
                publishedRules.remove(rule.id());
            } else {
                publishedRules.put(rule.id(), previous);
            }
            restoreInstrumentationState(method, signature, oldActive);
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
        boolean oldActive = hasActiveRules(oldRuleSet);
        try {
            rulePublisher.remove(method, ruleId);
            publishedRules.remove(ruleId);
            applyInstrumentationTransition(method, signature, oldActive, hasActiveRules(ruleRegistry.rules(methodKey)));
            eventBuffer.record("rule.delete", actor, ruleId, methodKey.toString(), "Deleted rule");
        } catch (RuntimeException e) {
            ruleRegistry.replace(methodKey, oldRuleSet);
            publishedRules.put(ruleId, publishedRule);
            restoreInstrumentationState(method, signature, oldActive);
            eventBuffer.record("rule.delete.failed", actor, ruleId, methodKey.toString(), e.getMessage());
            throw e;
        }
    }

    public void disableAll(boolean disabled) {
        globallyEnabled = !disabled;
        ruleDispatcher.enabled(globallyEnabled);
        eventBuffer.record(disabled ? "agent.disable-all" : "agent.enable-all", "system", null, null,
                "Global enabled=" + globallyEnabled);
    }

    public void resetAll(String actor) {
        RuntimeMockBridge.uninstall();
        ruleDispatcher.enabled(false);
        ruleRegistry.clear();
        publishedRules.clear();
        instrumentationRegistry.snapshot().forEach(instrumentationRegistry::unregister);
        transformerManager.close();
        transformerManager.install();
        RuntimeMockBridge.install(new AgentBridgeDispatcher(ruleDispatcher));
        globallyEnabled = true;
        ruleDispatcher.enabled(true);
        eventBuffer.record("agent.reset-all", actor, null, null, "All rules removed and transformer reset");
    }

    public List<RuleInfo> resetClass(String classId, String actor) {
        List<String> ruleIds = publishedRules.values().stream()
                .filter(rule -> matchesClass(rule, classId))
                .map(rule -> rule.rule().id())
                .toList();
        ruleIds.forEach(ruleId -> remove(ruleId, actor));
        eventBuffer.record("agent.reset-class", actor, null, classId,
                "Removed " + ruleIds.size() + " rules from class");
        return rules();
    }

    public void recordEvent(String type, String actor, String ruleId, String target, String message) {
        eventBuffer.record(type, actor, ruleId, target, message);
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
                globallyEnabled ? "enabled" : "disabled",
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
        RuntimeMockBridge.uninstall();
        ruleRegistry.clear();
        publishedRules.clear();
        transformerManager.close();
        scriptCompiler.close();
        cleanupExecutor.shutdownNow();
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
}
