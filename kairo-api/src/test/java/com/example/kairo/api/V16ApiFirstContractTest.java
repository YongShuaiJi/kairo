package com.example.kairo.api;

import com.example.kairo.api.automation.AutomationSession;
import com.example.kairo.api.automation.AutomationSessionResource;
import com.example.kairo.api.automation.AutomationSessionStatus;
import com.example.kairo.api.automation.EnhancementCandidate;
import com.example.kairo.api.automation.EnhancementContextBundle;
import com.example.kairo.api.automation.ScriptApiSurface;
import com.example.kairo.api.error.ApiError;
import com.example.kairo.api.error.ErrorCategory;
import com.example.kairo.api.error.ErrorTarget;
import com.example.kairo.api.error.SuggestedAction;
import com.example.kairo.api.operation.Operation;
import com.example.kairo.api.operation.OperationEvent;
import com.example.kairo.api.operation.OperationStatus;
import com.example.kairo.api.operation.OperationType;
import com.example.kairo.api.paging.Page;
import com.example.kairo.api.paging.PageRequest;
import com.example.kairo.api.write.ImpactSummary;
import com.example.kairo.api.write.PreviewResult;
import com.example.kairo.api.write.RiskLevel;
import com.example.kairo.api.write.WriteMeta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V1.6 API-First contract sanity: the frozen DTOs enforce the invariants the
 * plan depends on (error model completeness, pagination/operation shape,
 * automation narrowing, preview token, context-bundle size cap).
 */
class V16ApiFirstContractTest {

    @Test
    void apiErrorCarriesFullV16Contract() {
        ApiError error = ApiError.of("RESOURCE_VERSION_CONFLICT", "version changed",
                        ErrorCategory.CONFLICT, true)
                .withTarget(ErrorTarget.bodyField("expectedVersion"))
                .withCorrelationId("corr-1")
                .withSuggestedActions(List.of(SuggestedAction.safe("REFRESH_RESOURCE", "reload and retry")));

        assertThat(error.code()).isEqualTo("RESOURCE_VERSION_CONFLICT");
        assertThat(error.category()).isEqualTo(ErrorCategory.CONFLICT);
        assertThat(error.retryable()).isTrue();
        assertThat(error.field()).isEqualTo("expectedVersion");
        assertThat(error.path()).isEqualTo("/expectedVersion");
        assertThat(error.location()).isEqualTo("body");
        assertThat(error.correlationId()).isEqualTo("corr-1");
        assertThat(error.suggestedActions()).hasSize(1);
        assertThat(error.suggestedActions().get(0).safe()).isTrue();
    }

    @Test
    void apiErrorRejectsBlankCode() {
        assertThatThrownBy(() -> ApiError.of(" ", "m", ErrorCategory.VALIDATION, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pageDerivesHasMoreFromCursor() {
        assertThat(Page.of(List.of("a"), "next", 3).hasMore()).isTrue();
        assertThat(Page.of(List.of("a")).hasMore()).isFalse();
    }

    @Test
    void pageRequestClampsLimit() {
        assertThat(new PageRequest(null, null).effectiveLimit()).isEqualTo(50);
        assertThat(new PageRequest(99999, null).effectiveLimit()).isEqualTo(200);
        assertThat(new PageRequest(0, "  ").effectiveCursor()).isNull();
    }

    @Test
    void operationIsTerminalAndCarriesRevertLink() {
        Operation op = new Operation("op-1", OperationType.RULE_PUBLISH, OperationStatus.SUCCEEDED,
                "rule", "r-1", RiskLevel.HIGH, null, 100, Map.of("ruleVersionId", "rv-1"),
                null, "op-revert-1", "corr", "alice", 1L, 2L, 2L);
        assertThat(op.isTerminal()).isTrue();
        assertThat(op.revertOperationId()).isEqualTo("op-revert-1");
        assertThat(op.riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void operationEventIsAppendOnlyShaped() {
        OperationEvent ev = new OperationEvent("op-1", 1, "DISPATCHED", 1L, "alice", Map.of());
        assertThat(ev.sequence()).isEqualTo(1);
        assertThat(ev.detail()).isEmpty();
    }

    @Test
    void automationSessionNarrowsProfileAndTracksResources() {
        AutomationSession session = new AutomationSession("s-1", "ai-bot", "mcp", "app-1",
                null, null, "agent-1", CapabilityProfile.SAFE, 60_000L, 99_999L,
                AutomationSessionStatus.ACTIVE, RiskLevel.MEDIUM,
                List.of(new AutomationSessionResource("script-session", "ss-1", true, 1L)),
                Map.of(), "corr", 1L, 1L, 2L);
        assertThat(session.maxCapabilityProfile()).isEqualTo(CapabilityProfile.SAFE);
        assertThat(session.createdResources()).hasSize(1);
        assertThat(session.isTerminal()).isFalse();
    }

    @Test
    void automationSessionRejectsNonPositiveTtl() {
        assertThatThrownBy(() -> new AutomationSession("s-1", "ai-bot", "mcp", "app-1",
                null, null, null, CapabilityProfile.SAFE, 0L, 1L,
                AutomationSessionStatus.CREATED, RiskLevel.LOW, List.of(), Map.of(), "", 0, 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void previewResultRequiresTokenAndRevision() {
        PreviewResult preview = new PreviewResult("tok", 7L, RiskLevel.MEDIUM,
                new ImpactSummary(List.of(new ImpactSummary.AffectedResource("rule", "r-1")),
                        "app:checkout/env:prod", "single-instance", true, 1),
                Map.of("diff", "added"), 99_999L);
        assertThat(preview.previewToken()).isEqualTo("tok");
        assertThat(preview.revision()).isEqualTo(7L);
        assertThat(preview.impact().reversible()).isTrue();
        assertThatThrownBy(() -> new PreviewResult(" ", 7L, RiskLevel.LOW, null, null, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writeMetaToleratesNullOptionals() {
        WriteMeta meta = new WriteMeta(null, null, null, null, null, false);
        assertThat(meta.idempotencyKey()).isNull();
        assertThat(meta.dryRun()).isFalse();
    }

    @Test
    void enhancementContextBundleEnforcesSizeCapConstant() {
        assertThat(EnhancementContextBundle.MAX_SIZE_BYTES).isEqualTo(256 * 1024);
        EnhancementCandidate candidate = new EnhancementCandidate("t-1", "com.x.Foo", "bar",
                "()V", "cl-1", 0.92, "name-exact", SupportLevel.SUPPORTED, ProxyType.PLAIN);
        EnhancementContextBundle bundle = new EnhancementContextBundle(1, "s-1",
                List.of(candidate), List.of(), List.of(), List.of(),
                new ScriptApiSurface(CapabilityProfile.SAFE, Map.of(), List.of(), Map.of(), Map.of()),
                2048, 1L);
        assertThat(bundle.candidates()).hasSize(1);
        assertThat(bundle.candidates().get(0).confidence()).isEqualTo(0.92);
        assertThatThrownBy(() -> new EnhancementCandidate("t-1", "c", "m", "()V", "cl", 1.5, "r", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
