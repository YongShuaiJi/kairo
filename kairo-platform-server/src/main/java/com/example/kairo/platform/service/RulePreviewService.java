package com.example.kairo.platform.service;

import com.example.kairo.api.write.ImpactSummary;
import com.example.kairo.api.write.RiskLevel;
import com.example.kairo.platform.api.dto.CreateRuleRequest;
import com.example.kairo.platform.api.dto.RevertHint;
import com.example.kairo.platform.api.dto.RuleMatcherDto;
import com.example.kairo.platform.api.dto.RulePreviewRequest;
import com.example.kairo.platform.api.dto.RulePreviewResponse;
import com.example.kairo.platform.api.dto.RuleScriptDto;
import com.example.kairo.platform.api.dto.RuleTargetDto;
import com.example.kairo.platform.api.dto.RuleTargetMatcherDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side canonical rule preview/assembly (V1.6 &sect;5.3). Owns the business
 * defaults the web workbench previously assembled client-side (status, risk,
 * capabilities, target/matcher shape), validates the script, and returns a
 * preview token/revision, structured impact/risk and revert guidance so the
 * caller can forward {@code payload} verbatim to {@code /api/v1/rules} or
 * {@code /api/v1/rules/{id}/versions}.
 */
@Service
public final class RulePreviewService {

    private static final List<String> DEFAULT_CAPABILITIES = List.of("RETURN_VALUE", "THROW_EXCEPTION");
    private static final String DEFAULT_PROTOCOL = "JAVA_METHOD";
    private static final String DEFAULT_STATUS = "ENABLED";

    private final ScriptWorkbenchService scriptWorkbenchService;
    private final RbacService rbacService;
    private final Clock clock;

    @Autowired
    public RulePreviewService(ScriptWorkbenchService scriptWorkbenchService,
                              RbacService rbacService) {
        this(scriptWorkbenchService, rbacService, Clock.systemUTC());
    }

    RulePreviewService(ScriptWorkbenchService scriptWorkbenchService,
                       RbacService rbacService, Clock clock) {
        this.scriptWorkbenchService = scriptWorkbenchService;
        this.rbacService = rbacService;
        this.clock = clock;
    }

    public RulePreviewResponse preview(RequestContext context, RulePreviewRequest request) {
        rbacService.require(context, "RULE_MANAGE");
        requireText(request.name(), "name");
        requireText(request.applicationId(), "applicationId");
        requireText(request.environmentId(), "environmentId");
        requireText(request.className(), "className");
        requireText(request.methodName(), "methodName");
        requireText(request.classLoaderId(), "classLoaderId");
        requireText(request.methodDescriptor(), "methodDescriptor");
        String phase = normalizePhase(request.executionPhase());
        requireText(request.script(), "script");

        RiskLevel risk = riskFor(phase, request.script());
        RuleScriptDto script = new RuleScriptDto(phase, request.script());
        RuleMatcherDto matcher = new RuleMatcherDto(phase);
        RuleTargetMatcherDto targetMatcher = new RuleTargetMatcherDto(
                request.classId(), request.classLoaderId(), request.methodDescriptor());
        RuleTargetDto target = new RuleTargetDto(DEFAULT_PROTOCOL, request.className(),
                request.methodName(), targetMatcher, null, null);
        CreateRuleRequest payload = new CreateRuleRequest(
                null, request.name(), request.applicationId(), request.environmentId(),
                DEFAULT_STATUS, DEFAULT_STATUS, risk.name(),
                script, matcher, List.of(target), null, DEFAULT_CAPABILITIES,
                null, null, null, null, request.reason());

        Map<String, Object> validation = scriptWorkbenchService.validate(
                Map.of("script", Map.of("phase", phase, "script", request.script())));

        String targetId = firstNonBlank(request.classId(),
                request.className() + "#" + request.methodName() + request.methodDescriptor());
        ImpactSummary impact = new ImpactSummary(
                List.of(new ImpactSummary.AffectedResource("rule-target", targetId)),
                "app:" + request.applicationId(), "single-instance", true, 1);

        long revision = clock.instant().toEpochMilli();
        String previewToken = "rule-prev-" + UUID.randomUUID();
        RevertHint revert = new RevertHint("DISABLE_RULE_VERSION",
                "停用规则版本后，系统在保留期结束后自动删除；已发布的版本可通过发布管理卸载立即生效回滚。",
                List.of("POST /api/v1/rules/{id}/versions/{version}/disable",
                        "POST /api/v1/operation-plans/{id}/unload (已发布版本的立即回滚)",
                        "保留期结束后自动删除（当前为 30 天）"));

        return new RulePreviewResponse(payload, previewToken, revision, risk, impact, validation, revert);
    }

    private static RiskLevel riskFor(String phase, String script) {
        String source = script == null ? "" : script.toLowerCase(java.util.Locale.ROOT);
        if (source.contains("system.exit") || source.contains("runtime.exec")) {
            return RiskLevel.HIGH;
        }
        if ("THROWS".equals(phase) || source.contains("throwexception")) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    private static String normalizePhase(String phase) {
        if (phase == null || phase.isBlank()) {
            throw PlatformException.badRequest("FIELD_REQUIRED", "Missing required field: executionPhase");
        }
        String normalized = phase.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "BEFORE", "RETURN", "THROWS" -> normalized;
            default -> throw PlatformException.badRequest("INVALID_PHASE",
                    "executionPhase must be BEFORE, RETURN, or THROWS");
        };
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw PlatformException.badRequest("FIELD_REQUIRED", "Missing required field: " + name);
        }
    }
}
