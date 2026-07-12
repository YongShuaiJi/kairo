package com.example.kairo.agent.core.script;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.MockRule;
import com.example.kairo.api.ScriptDiagnostic;
import com.example.kairo.api.ScriptPolicyRevision;
import com.example.kairo.api.ScriptSessionResult;
import com.example.kairo.api.ScriptSessionSpec;
import com.example.kairo.api.ScriptSessionStatus;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.core.CompiledRule;
import com.example.kairo.core.MethodDescriptor;
import com.example.kairo.core.MethodKey;
import com.example.kairo.core.RulePublisher;
import com.example.kairo.core.RuleRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-level coverage of the {@link ScriptSessionManager} lifecycle: state transitions, safety
 * limits, local TTL expiry (lazy and scheduled, independent of any control-plane connection),
 * hit-cap expiry, promotion (which must not widen permissions or scope) and emergency
 * deactivation. The host is a fake wrapping a real {@link RulePublisher} so the ClassLoader-aware
 * compile path is exercised end-to-end without instrumentation.
 */
class ScriptSessionManagerTest {

    private static final ScriptPolicyRevision REVISION = new ScriptPolicyRevision(1, "test");
    private static final long START_MILLIS = 1_700_000_000_000L;

    private MutableClock clock;
    private AgentScriptCompilerFactory factory;
    private FakeHost host;
    private ScriptSessionManager manager;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START_MILLIS);
        factory = new AgentScriptCompilerFactory(AgentScriptCompilerFactory.class.getClassLoader());
        host = new FakeHost(factory);
        manager = new ScriptSessionManager(host, factory, this::resolveTarget, clock, ScriptSessionLimits.defaults());
    }

    @AfterEach
    void tearDown() {
        manager.close();
        factory.close();
    }

    // ------------------------------------------------------------ create

    @Test
    void createReturnsCreatedSessionWithLocalDeadline() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        ScriptSessionResult result = manager.create(spec("s1", method, "return mock.proceed()",
                CapabilityProfile.SAFE, 60_000L, 10L));

        assertThat(result.status()).isEqualTo(ScriptSessionStatus.CREATED);
        assertThat(result.createdAt()).isEqualTo(START_MILLIS);
        assertThat(result.expiresAt()).isEqualTo(START_MILLIS + 60_000L);
        assertThat(result.hitCount()).isZero();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(host.eventTypes()).contains("script.session.create");
    }

    @Test
    void createRejectsTtlAboveLimit() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        long over = ScriptSessionLimits.DEFAULT_MAX_TTL_MILLIS + 1_000L;
        assertThatThrownBy(() -> manager.create(spec("s1", method, "return mock.proceed()",
                CapabilityProfile.SAFE, over, 10L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TTL");
    }

    @Test
    void createRejectsMaxHitsAboveLimit() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        long over = ScriptSessionLimits.DEFAULT_MAX_HITS_CAP + 1L;
        assertThatThrownBy(() -> manager.create(spec("s1", method, "return mock.proceed()",
                CapabilityProfile.SAFE, 60_000L, over)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxHits");
    }

    @Test
    void createRejectsDuplicateSessionId() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s1", method, "return mock.proceed()", CapabilityProfile.SAFE, 60_000L, 10L));
        assertThatThrownBy(() -> manager.create(spec("s1", method, "return mock.proceed()",
                CapabilityProfile.SAFE, 60_000L, 10L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void createEnforcesSingleInstancePerTargetByDefault() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s1", method, "return mock.proceed()", CapabilityProfile.SAFE, 60_000L, 10L));
        // A second non-terminal session for the same target is rejected.
        assertThatThrownBy(() -> manager.create(spec("s2", method, "return mock.proceed()",
                CapabilityProfile.SAFE, 60_000L, 10L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Target session limit");

        // Once the first session reaches a terminal state, the slot is freed.
        manager.revert("s1");
        assertThat(manager.create(spec("s2", method, "return mock.proceed()",
                CapabilityProfile.SAFE, 60_000L, 10L)).status()).isEqualTo(ScriptSessionStatus.CREATED);
    }

    @Test
    void createAllowsConcurrentSessionsForDifferentTargets() throws Exception {
        Method echo = SessionTarget.class.getMethod("echo", String.class);
        Method score = SessionTarget.class.getMethod("score", int.class);
        manager.create(spec("s-echo", echo, "return mock.proceed()", CapabilityProfile.SAFE, 60_000L, 10L));
        assertThat(manager.create(spec("s-score", score, "return mock.proceed()",
                CapabilityProfile.SAFE, 60_000L, 10L)).status()).isEqualTo(ScriptSessionStatus.CREATED);
    }

    @Test
    void createRejectsUnresolvableTarget() {
        MethodSelector missing = new MethodSelector("no.such.Class",
                ClassLoaderIdentity.idOf(SessionTarget.class.getClassLoader()),
                "echo", "()Ljava/lang/String;");
        ScriptSessionSpec bad = new ScriptSessionSpec("s1", "agent-1", missing,
                "return mock.proceed()", CapabilityProfile.SAFE, REVISION, 60_000L, 10L, "tester");
        assertThatThrownBy(() -> manager.create(bad))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(manager.sessions()).isEmpty();
    }

    @Test
    void createRejectsSyntheticMethod() throws Exception {
        // A bridge method on a generic subclass is synthetic/bridge and must be rejected.
        Method bridge = Arrays.stream(StringValue.class.getDeclaredMethods())
                .filter(Method::isBridge)
                .findFirst()
                .orElseThrow();
        assertThatThrownBy(() -> manager.create(spec("s1", bridge, "return mock.proceed()",
                CapabilityProfile.SAFE, 60_000L, 10L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Synthetic and bridge");
    }

    // ------------------------------------------------------------ validate

    @Test
    void validateCompilesScriptAndMovesToValidated() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s1", method, "return mock.returnValue('ok')",
                CapabilityProfile.UNRESTRICTED, 60_000L, 10L));

        ScriptSessionResult result = manager.validate("s1");

        assertThat(result.status()).isEqualTo(ScriptSessionStatus.VALIDATED);
        assertThat(result.diagnostics()).isEmpty();
        assertThat(host.eventTypes()).contains("script.session.validate");
        // No trial rule is published by validate.
        assertThat(host.lastApplied).isNull();
    }

    @Test
    void validateFailureMarksSessionFailedWithCompilationDiagnostic() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s1", method, "new java.io.File('/tmp/kairo-forbidden')",
                CapabilityProfile.SAFE, 60_000L, 10L));

        ScriptSessionResult result = manager.validate("s1");

        assertThat(result.status()).isEqualTo(ScriptSessionStatus.FAILED);
        assertThat(result.diagnostics()).hasSize(1);
        ScriptDiagnostic diagnostic = result.diagnostics().get(0);
        assertThat(diagnostic.phase()).isEqualTo(ScriptDiagnostic.Phase.COMPILATION);
        assertThat(diagnostic.severity()).isEqualTo(ScriptDiagnostic.Severity.ERROR);
        assertThat(diagnostic.code()).isEqualTo("SCRIPT_COMPILE_ERROR");
        assertThat(diagnostic.targetClassLoaderId())
                .isEqualTo(ClassLoaderIdentity.idOf(SessionTarget.class.getClassLoader()));
        assertThat(diagnostic.suggestion()).isNotBlank();
        assertThat(host.eventTypes()).contains("script.session.validate.failed");
    }

    @Test
    void validateRequiresCreatedState() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s1", method, "return mock.proceed()", CapabilityProfile.SAFE, 60_000L, 10L));
        manager.validate("s1");
        assertThatThrownBy(() -> manager.validate("s1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("validate");
    }

    @Test
    void failedSessionCannotBeValidatedOrApplied() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s1", method, "new java.io.File('/tmp/kairo-forbidden')",
                CapabilityProfile.SAFE, 60_000L, 10L));
        manager.validate("s1");
        assertThat(manager.result("s1").status()).isEqualTo(ScriptSessionStatus.FAILED);

        assertThatThrownBy(() -> manager.validate("s1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> manager.apply("s1")).isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------ apply

    @Test
    void applyPublishesBoundedTrialRule() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s1", method, "return mock.returnValue('mocked')",
                CapabilityProfile.UNRESTRICTED, 60_000L, 5L));
        manager.validate("s1");

        ScriptSessionResult result = manager.apply("s1");

        assertThat(result.status()).isEqualTo(ScriptSessionStatus.APPLIED);
        assertThat(result.hitCount()).isZero();
        assertThat(host.lastApplied).isNotNull();
        assertThat(host.lastApplied.rule().maxHits()).isEqualTo(5L);
        assertThat(host.lastApplied.rule().expireAt()).isEqualTo(START_MILLIS + 60_000L);
        assertThat(host.lastApplied.rule().scriptSessionSource()).isEqualTo("s1");
        assertThat(host.ruleExistsFor(method, "s1")).isTrue();
        assertThat(host.eventTypes()).contains("script.session.apply");
    }

    @Test
    void applyRequiresValidatedState() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s1", method, "return mock.proceed()", CapabilityProfile.SAFE, 60_000L, 10L));
        assertThatThrownBy(() -> manager.apply("s1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("apply");
    }

    @Test
    void applyFailureMarksSessionFailedAndPublishesNoRule() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s1", method, "return mock.returnValue('ok')",
                CapabilityProfile.UNRESTRICTED, 60_000L, 10L));
        manager.validate("s1");
        host.applyFailure = new IllegalStateException("instrumentation unavailable");

        ScriptSessionResult result = manager.apply("s1");

        assertThat(result.status()).isEqualTo(ScriptSessionStatus.FAILED);
        assertThat(result.diagnostics()).hasSize(1);
        assertThat(result.diagnostics().get(0).phase()).isEqualTo(ScriptDiagnostic.Phase.EXECUTION);
        assertThat(result.diagnostics().get(0).code()).isEqualTo("SCRIPT_APPLY_ERROR");
        assertThat(host.ruleExistsFor(method, "s1")).isFalse();
        assertThat(host.eventTypes()).contains("script.session.apply.failed");
    }

    // ------------------------------------------------------------ revert

    @Test
    void revertRemovesTrialRuleAndIsIdempotent() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s1", method, "return mock.returnValue('mocked')",
                CapabilityProfile.UNRESTRICTED, 60_000L, 10L));
        manager.validate("s1");
        manager.apply("s1");
        assertThat(host.ruleExistsFor(method, "s1")).isTrue();

        ScriptSessionResult result = manager.revert("s1");

        assertThat(result.status()).isEqualTo(ScriptSessionStatus.REVERTED);
        assertThat(host.ruleExistsFor(method, "s1")).isFalse();

        // Reverting again is a no-op (idempotent DELETE semantics).
        assertThat(manager.revert("s1").status()).isEqualTo(ScriptSessionStatus.REVERTED);
    }

    @Test
    void revertCreatedSessionSkipsRuleRemoval() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s1", method, "return mock.proceed()", CapabilityProfile.SAFE, 60_000L, 10L));
        assertThat(manager.revert("s1").status()).isEqualTo(ScriptSessionStatus.REVERTED);
        assertThat(host.eventTypes()).contains("script.session.revert");
    }

    // ------------------------------------------------------------ promote

    @Test
    void promotePublishesFormalRuleWithSameProfileAndScope() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s1", method, "return mock.returnValue('mocked')",
                CapabilityProfile.EXTENDED, 60_000L, 5L));
        manager.validate("s1");
        manager.apply("s1");

        ScriptSessionResult result = manager.promote("s1", "operator");

        assertThat(result.status()).isEqualTo(ScriptSessionStatus.REVERTED);
        assertThat(host.lastApplied).isNotNull();
        MockRule formal = host.lastApplied.rule();
        // The formal rule keeps the session's permissions and scope exactly.
        assertThat(formal.capabilityProfile()).isEqualTo(CapabilityProfile.EXTENDED);
        assertThat(formal.policyRevision()).isEqualTo(REVISION);
        assertThat(formal.target().className()).isEqualTo(SessionTarget.class.getName());
        assertThat(formal.target().methodName()).isEqualTo("echo");
        assertThat(formal.script()).contains("returnValue('mocked')");
        // The trial's TTL and hit cap are dropped, but no permission/scope is widened.
        assertThat(formal.maxHits()).isZero();
        assertThat(formal.expireAt()).isZero();
        assertThat(formal.scriptSessionSource()).isEqualTo("s1");
        assertThat(host.eventTypes()).contains("script.session.promote");
    }

    @Test
    void promoteFromValidatedPublishesFormalRule() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s1", method, "return mock.returnValue('mocked')",
                CapabilityProfile.UNRESTRICTED, 60_000L, 5L));
        manager.validate("s1");
        // No apply: the formal rule is published directly from a validated session.
        manager.promote("s1", "operator");
        assertThat(host.lastApplied.rule().capabilityProfile()).isEqualTo(CapabilityProfile.UNRESTRICTED);
        assertThat(host.ruleExistsFor(method, "s1")).isTrue();
    }

    @Test
    void promoteRequiresValidatedOrApplied() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s1", method, "return mock.proceed()", CapabilityProfile.SAFE, 60_000L, 10L));
        assertThatThrownBy(() -> manager.promote("s1", "operator"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("promote");
    }

    @Test
    void revertingPromotedSessionDoesNotDeleteFormalRule() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s1", method, "return mock.returnValue('mocked')",
                CapabilityProfile.UNRESTRICTED, 60_000L, 5L));
        manager.validate("s1");
        manager.apply("s1");
        manager.promote("s1", "operator");
        assertThat(host.ruleExistsFor(method, "s1")).isTrue();

        // The session is terminal; reverting it must leave the formal rule intact.
        manager.revert("s1");
        assertThat(host.ruleExistsFor(method, "s1")).isTrue();
    }

    // ------------------------------------------------------------ TTL expiry

    @Test
    void ttlExpiresLazilyOnAccessWhenDeadlinePasses() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s1", method, "return mock.returnValue('mocked')",
                CapabilityProfile.UNRESTRICTED, 1_000L, 10L));
        manager.validate("s1");
        manager.apply("s1");
        assertThat(host.ruleExistsFor(method, "s1")).isTrue();

        clock.advance(1_001L);
        ScriptSessionResult result = manager.result("s1");

        assertThat(result.status()).isEqualTo(ScriptSessionStatus.EXPIRED);
        assertThat(host.ruleExistsFor(method, "s1")).isFalse();
        assertThat(host.eventTypes()).contains("script.session.expire");
    }

    @Test
    void ttlExpiresViaScheduledSweep() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s1", method, "return mock.returnValue('mocked')",
                CapabilityProfile.UNRESTRICTED, 500L, 10L));
        manager.validate("s1");
        manager.apply("s1");

        clock.advance(501L);
        manager.expireDue();

        assertThat(manager.result("s1").status()).isEqualTo(ScriptSessionStatus.EXPIRED);
        assertThat(host.ruleExistsFor(method, "s1")).isFalse();
    }

    @Test
    void ttlExpiresIndependentOfPlatformOrClientConnectivity() throws Exception {
        // The manager holds no Platform or client handle; expiry is driven solely by the local
        // clock sweep. Advancing time alone is sufficient, simulating the control plane being
        // offline or the creating client having disconnected.
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s1", method, "return mock.returnValue('mocked')",
                CapabilityProfile.UNRESTRICTED, 1_000L, 10L));
        manager.validate("s1");
        manager.apply("s1");

        // No further interaction: just advance the local clock and sweep.
        clock.advance(1_000L);
        manager.expireDue();

        assertThat(manager.result("s1").status()).isEqualTo(ScriptSessionStatus.EXPIRED);
    }

    @Test
    void nonAppliedSessionAlsoExpiresByTtl() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s1", method, "return mock.proceed()", CapabilityProfile.SAFE, 1_000L, 10L));
        // CREATED, never applied.
        clock.advance(1_001L);
        manager.expireDue();
        assertThat(manager.result("s1").status()).isEqualTo(ScriptSessionStatus.EXPIRED);
    }

    @Test
    void hitCapExpiresAppliedSession() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s1", method, "return mock.returnValue('mocked')",
                CapabilityProfile.UNRESTRICTED, 60_000L, 3L));
        manager.validate("s1");
        manager.apply("s1");

        // Simulate three matched invocations reaching the hit cap.
        CompiledRule applied = host.lastApplied;
        for (int i = 0; i < 3; i++) {
            assertThat(applied.tryClaimHit()).isTrue();
        }
        assertThat(applied.tryClaimHit()).isFalse();

        manager.expireDue();

        ScriptSessionResult result = manager.result("s1");
        assertThat(result.status()).isEqualTo(ScriptSessionStatus.EXPIRED);
        assertThat(result.hitCount()).isEqualTo(3L);
        assertThat(host.ruleExistsFor(method, "s1")).isFalse();
        assertThat(host.lastEventMessage()).contains("hit cap");
    }

    @Test
    void sessionDoesNotExpireBeforeDeadline() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s1", method, "return mock.returnValue('mocked')",
                CapabilityProfile.UNRESTRICTED, 10_000L, 10L));
        manager.validate("s1");
        manager.apply("s1");

        clock.advance(5_000L);
        manager.expireDue();
        assertThat(manager.result("s1").status()).isEqualTo(ScriptSessionStatus.APPLIED);
    }

    // ------------------------------------------------------------ emergency deactivation

    @Test
    void deactivateAllRevertsEveryNonTerminalSession() throws Exception {
        Method echo = SessionTarget.class.getMethod("echo", String.class);
        Method score = SessionTarget.class.getMethod("score", int.class);
        manager.create(spec("s-echo", echo, "return mock.returnValue('a')",
                CapabilityProfile.UNRESTRICTED, 60_000L, 10L));
        manager.validate("s-echo");
        manager.apply("s-echo");
        manager.create(spec("s-score", score, "return mock.returnValue(1)",
                CapabilityProfile.UNRESTRICTED, 60_000L, 10L));
        manager.validate("s-score");
        assertThat(host.ruleExistsFor(echo, "s-echo")).isTrue();

        int count = manager.deactivateAll("operator");

        assertThat(count).isEqualTo(2);
        assertThat(manager.result("s-echo").status()).isEqualTo(ScriptSessionStatus.REVERTED);
        assertThat(manager.result("s-score").status()).isEqualTo(ScriptSessionStatus.REVERTED);
        assertThat(host.ruleExistsFor(echo, "s-echo")).isFalse();
        assertThat(host.eventTypes()).filteredOn("script.session.deactivate"::equals).hasSize(2);
    }

    @Test
    void deactivateTargetMatchesByClassId() throws Exception {
        Method echo = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s-echo", echo, "return mock.returnValue('a')",
                CapabilityProfile.UNRESTRICTED, 60_000L, 10L));
        manager.validate("s-echo");
        manager.apply("s-echo");
        String classId = classIdOf(SessionTarget.class);

        int count = manager.deactivateTarget(classId, "operator");

        assertThat(count).isEqualTo(1);
        assertThat(manager.result("s-echo").status()).isEqualTo(ScriptSessionStatus.REVERTED);
        assertThat(host.ruleExistsFor(echo, "s-echo")).isFalse();
    }

    @Test
    void deactivateTargetMatchesByClassName() throws Exception {
        Method echo = SessionTarget.class.getMethod("echo", String.class);
        manager.create(spec("s-echo", echo, "return mock.returnValue('a')",
                CapabilityProfile.UNRESTRICTED, 60_000L, 10L));
        manager.validate("s-echo");
        manager.apply("s-echo");

        int count = manager.deactivateTarget(SessionTarget.class.getName(), "operator");

        assertThat(count).isEqualTo(1);
        assertThat(manager.result("s-echo").status()).isEqualTo(ScriptSessionStatus.REVERTED);
    }

    @Test
    void deactivateTargetLeavesOtherTargetsAlone() throws Exception {
        Method echo = SessionTarget.class.getMethod("echo", String.class);
        Method score = SessionTarget.class.getMethod("score", int.class);
        manager.create(spec("s-echo", echo, "return mock.returnValue('a')",
                CapabilityProfile.UNRESTRICTED, 60_000L, 10L));
        manager.validate("s-echo");
        manager.apply("s-echo");
        manager.create(spec("s-score", score, "return mock.returnValue(1)",
                CapabilityProfile.UNRESTRICTED, 60_000L, 10L));
        manager.validate("s-score");
        manager.apply("s-score");

        int count = manager.deactivateTarget("no.such.Class", "operator");
        assertThat(count).isZero();
        assertThat(manager.result("s-echo").status()).isEqualTo(ScriptSessionStatus.APPLIED);
        assertThat(manager.result("s-score").status()).isEqualTo(ScriptSessionStatus.APPLIED);
    }

    // ------------------------------------------------------------ queries / errors

    @Test
    void sessionsReturnsOrderedSnapshots() throws Exception {
        Method echo = SessionTarget.class.getMethod("echo", String.class);
        Method score = SessionTarget.class.getMethod("score", int.class);
        manager.create(spec("beta", echo, "return mock.proceed()", CapabilityProfile.SAFE, 60_000L, 10L));
        manager.create(spec("alpha", score, "return mock.proceed()", CapabilityProfile.SAFE, 60_000L, 10L));

        List<ScriptSessionResult> all = manager.sessions();
        assertThat(all).hasSize(2);
        assertThat(all.get(0).sessionId()).isEqualTo("alpha");
        assertThat(all.get(1).sessionId()).isEqualTo("beta");
    }

    @Test
    void unknownSessionOperationsThrow() {
        assertThatThrownBy(() -> manager.result("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.validate("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.apply("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.revert("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.promote("nope", "operator")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closedManagerRejectsNewSessions() throws Exception {
        Method method = SessionTarget.class.getMethod("echo", String.class);
        manager.close();
        assertThatThrownBy(() -> manager.create(spec("s1", method, "return mock.proceed()",
                CapabilityProfile.SAFE, 60_000L, 10L)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void configurableLimitsAllowHigherTtlAndHits() throws Exception {
        ScriptSessionLimits relaxed = ScriptSessionLimits.builder()
                .maxTtlMillis(120_000L)
                .maxHitsCap(1_000L)
                .maxConcurrentPerTarget(2)
                .build();
        try (AgentScriptCompilerFactory f = new AgentScriptCompilerFactory(
                AgentScriptCompilerFactory.class.getClassLoader())) {
            FakeHost h = new FakeHost(f);
            try (ScriptSessionManager m = new ScriptSessionManager(h, f, this::resolveTarget,
                    new MutableClock(START_MILLIS), relaxed)) {
                Method method = SessionTarget.class.getMethod("echo", String.class);
                assertThat(m.create(spec("s1", method, "return mock.proceed()",
                        CapabilityProfile.SAFE, 120_000L, 1_000L)).status()).isEqualTo(ScriptSessionStatus.CREATED);
                // Two concurrent sessions for the same target are now allowed.
                assertThat(m.create(spec("s2", method, "return mock.proceed()",
                        CapabilityProfile.SAFE, 60_000L, 10L)).status()).isEqualTo(ScriptSessionStatus.CREATED);
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private ScriptSessionTarget resolveTarget(MethodSelector target) {
        try {
            Class<?> type = Class.forName(target.className(), false, getClass().getClassLoader());
            Method method = Arrays.stream(type.getDeclaredMethods())
                    .filter(m -> m.getName().equals(target.methodName()))
                    .filter(m -> MethodDescriptor.of(m).equals(target.methodDescriptor()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Method not found: " + target.className() + "#" + target.methodName()
                                    + target.methodDescriptor()));
            return new ScriptSessionTarget(method, classIdOf(type), type.getName());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Class not found: " + target.className(), e);
        }
    }

    private static String classIdOf(Class<?> type) {
        String raw = ClassLoaderIdentity.idOf(type.getClassLoader()) + "|" + type.getName();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static ScriptSessionSpec spec(String id, Method method, String script,
                                          CapabilityProfile profile, long ttlMillis, long maxHits) {
        return new ScriptSessionSpec(id, "agent-1", selector(method), script, profile, REVISION,
                ttlMillis, maxHits, "tester");
    }

    private static MethodSelector selector(Method method) {
        return new MethodSelector(method.getDeclaringClass().getName(),
                ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()),
                method.getName(), MethodDescriptor.of(method));
    }

    /** Generic interface + covariant override so the compiler generates a real bridge method. */
    private interface Value<T> {
        T value();
    }

    public static final class StringValue implements Value<String> {
        @Override
        public String value() {
            return "value";
        }
    }

    /** A {@link ScriptSessionHost} backed by a real {@link RulePublisher} so compile is exercised. */
    private static final class FakeHost implements ScriptSessionHost {
        private final RuleRegistry registry = new RuleRegistry();
        private final RulePublisher publisher;
        private final Map<String, Method> methods = new ConcurrentHashMap<>();
        private final List<RuntimeEventRecord> events = new CopyOnWriteArrayList<>();
        private volatile CompiledRule lastApplied;
        private volatile RuntimeException applyFailure;

        FakeHost(AgentScriptCompilerFactory factory) {
            this.publisher = new RulePublisher(factory, registry);
        }

        @Override
        public CompiledRule applyTrialRule(Method targetMethod, MockRule rule, String actor) {
            if (applyFailure != null) {
                throw applyFailure;
            }
            methods.put(rule.id(), targetMethod);
            CompiledRule compiled = publisher.publish(targetMethod, rule);
            lastApplied = compiled;
            return compiled;
        }

        @Override
        public void revertTrialRule(String ruleId, String actor) {
            Method method = methods.remove(ruleId);
            if (method != null) {
                publisher.remove(method, ruleId);
            }
        }

        @Override
        public void recordSessionEvent(String type, String actor, String sessionId, String target, String message) {
            events.add(new RuntimeEventRecord(type, actor, sessionId, target, message));
        }

        List<String> eventTypes() {
            return events.stream().map(e -> e.type).toList();
        }

        String lastEventMessage() {
            return events.isEmpty() ? "" : events.get(events.size() - 1).message;
        }

        boolean ruleExistsFor(Method method, String ruleId) {
            return registry.rules(MethodKey.of(method)).all().stream()
                    .anyMatch(r -> r.rule().id().equals(ruleId));
        }
    }

    private record RuntimeEventRecord(String type, String actor, String sessionId, String target, String message) {
    }

    /** Clock whose current millis is mutable from the test, so TTL is deterministic. */
    private static final class MutableClock extends Clock {
        private volatile long millis;

        MutableClock(long initial) {
            this.millis = initial;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        void advance(long delta) {
            millis += delta;
        }
    }
}
