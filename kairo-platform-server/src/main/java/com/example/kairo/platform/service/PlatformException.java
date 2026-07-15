package com.example.kairo.platform.service;

import com.example.kairo.api.error.ErrorCategory;
import com.example.kairo.api.error.ErrorTarget;
import com.example.kairo.api.error.SuggestedAction;

import java.util.List;
import java.util.Map;

/**
 * Structured platform exception carrying the frozen V1.6 error contract
 * (&sect;2.4): stable code, {@link ErrorCategory category}, retryable flag,
 * structured details, an optional {@link ErrorTarget} (field/path/location)
 * and machine-readable {@link SuggestedAction suggested actions}.
 *
 * <p>Factory methods infer the {@link ErrorCategory} so existing call sites
 * remain backward compatible; callers may refine the target and suggested
 * actions via {@link #withTarget(ErrorTarget)} and
 * {@link #withSuggestions(List)}.
 */
public final class PlatformException extends RuntimeException {

    private final int status;
    private final String code;
    private final ErrorCategory category;
    private final boolean retryable;
    private final Map<String, Object> details;
    private final ErrorTarget target;
    private final List<SuggestedAction> suggestedActions;

    private PlatformException(int status, String code, ErrorCategory category, String message,
                              boolean retryable, Map<String, Object> details,
                              ErrorTarget target, List<SuggestedAction> suggestedActions) {
        super(localizedMessage(code, message));
        this.status = status;
        this.code = code;
        this.category = category == null ? inferCategory(code, status) : category;
        this.retryable = retryable;
        this.details = details == null ? Map.of() : details;
        this.target = target;
        this.suggestedActions = suggestedActions == null ? List.of() : List.copyOf(suggestedActions);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public ErrorCategory category() {
        return category;
    }

    public boolean retryable() {
        return retryable;
    }

    public Map<String, Object> details() {
        return details;
    }

    public ErrorTarget target() {
        return target;
    }

    public List<SuggestedAction> suggestedActions() {
        return suggestedActions;
    }

    public PlatformException withTarget(ErrorTarget target) {
        return new PlatformException(status, code, category, getMessage(), retryable, details,
                target, suggestedActions);
    }

    public PlatformException withSuggestions(List<SuggestedAction> actions) {
        return new PlatformException(status, code, category, getMessage(), retryable, details,
                target, actions);
    }

    public PlatformException withDetail(String key, Object value) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>(details);
        merged.put(key, value);
        return new PlatformException(status, code, category, getMessage(), retryable, merged,
                target, suggestedActions);
    }

    public static PlatformException badRequest(String code, String message) {
        return new PlatformException(400, code, ErrorCategory.VALIDATION, message, false, Map.of(), null, null);
    }

    public static PlatformException methodNotAllowed(String code, String message) {
        return new PlatformException(405, code, ErrorCategory.VALIDATION, message, false, Map.of(), null, null);
    }

    public static PlatformException forbidden(String capability) {
        return new PlatformException(403, "FORBIDDEN", ErrorCategory.AUTHORIZATION,
                "当前身份缺少所需权限：" + capability, false, Map.of("capability", capability), null,
                List.of(SuggestedAction.manual("REQUEST_CAPABILITY", "联系管理员授予 " + capability + " 权限")));
    }

    /** Token-scope denial (V1.6 &sect;5.1): subject holds the capability but the token scope excludes it. */
    public static PlatformException forbidden(String code, String message,
                                              Map<String, Object> details, List<SuggestedAction> actions) {
        return new PlatformException(403, code, ErrorCategory.AUTHORIZATION, message, false, details, null,
                actions == null ? List.of() : actions);
    }

    public static PlatformException unauthorized(String message) {
        return new PlatformException(401, "UNAUTHORIZED", ErrorCategory.AUTHENTICATION,
                message, false, Map.of(), null,
                List.of(SuggestedAction.safe("REAUTHENTICATE", "提供有效的 Bearer Token 后重试")));
    }

    /**
     * Authentication failure carrying a specific stable code (V1.6 &sect;2.4). Used when a
     * persisted token is structurally invalid (e.g. corrupted scope_json or max_sessions) so
     * authentication fails closed with a machine-readable code rather than a generic message.
     */
    public static PlatformException unauthorized(String code, String message) {
        return new PlatformException(401, code, ErrorCategory.AUTHENTICATION,
                message, false, Map.of(), null,
                List.of(SuggestedAction.safe("REAUTHENTICATE", "提供有效的 Bearer Token 后重试")));
    }

    public static PlatformException notFound(String resourceType, String resourceId) {
        return new PlatformException(404, "RESOURCE_NOT_FOUND", ErrorCategory.NOT_FOUND,
                "未找到资源：" + resourceType + "（" + resourceId + "）", false,
                Map.of("resourceType", resourceType, "resourceId", resourceId), null, null);
    }

    public static PlatformException conflict(String code, String message, Map<String, Object> details) {
        List<SuggestedAction> actions = "RESOURCE_VERSION_CONFLICT".equals(code)
                ? List.of(SuggestedAction.safe("REFRESH_RESOURCE", "重新读取资源最新版本后重试"))
                : List.of();
        return new PlatformException(409, code, ErrorCategory.CONFLICT, message, true, details, null, actions);
    }

    /** Capability-mismatch error (V1.6 &sect;5.2 / &sect;2.4 category=CAPABILITY). */
    public static PlatformException unsupportedCapability(String message, Map<String, Object> details) {
        return new PlatformException(409, "CAPABILITY_NOT_SUPPORTED", ErrorCategory.CAPABILITY,
                message, false, details, null,
                List.of(SuggestedAction.safe("REQUEST_PREVIEW", "降级为受支持的命令或升级 Agent")));
    }

    /** Rate-limited / quota error (&sect;2.4 category=RATE_LIMITED). */
    public static PlatformException rateLimited(String code, String message, Map<String, Object> details) {
        return new PlatformException(429, code, ErrorCategory.RATE_LIMITED, message, true, details, null,
                List.of(SuggestedAction.safe("BACKOFF_RETRY", "退避后重试")));
    }

    private static ErrorCategory inferCategory(String code, int status) {
        if (code == null) {
            return status >= 500 ? ErrorCategory.INTERNAL : ErrorCategory.VALIDATION;
        }
        return switch (code) {
            case "UNAUTHORIZED" -> ErrorCategory.AUTHENTICATION;
            case "FORBIDDEN" -> ErrorCategory.AUTHORIZATION;
            case "RESOURCE_NOT_FOUND" -> ErrorCategory.NOT_FOUND;
            case "RESOURCE_VERSION_CONFLICT", "AGENT_COMMAND_STATE_CONFLICT",
                 "USERNAME_CONFLICT", "SCRIPT_SESSION_TARGET_BUSY",
                 "FENCING_TOKEN_INVALID", "OPERATION_PLAN_INVALID_TRANSITION" -> ErrorCategory.CONFLICT;
            case "CAPABILITY_NOT_SUPPORTED" -> ErrorCategory.CAPABILITY;
            case "REDIS_UNAVAILABLE", "REDIS_FENCING_FAILED", "FENCING_SEQUENCE_FAILED",
                 "INTERNAL_ERROR" -> ErrorCategory.INTERNAL;
            default -> status >= 500 ? ErrorCategory.INTERNAL : ErrorCategory.VALIDATION;
        };
    }

    private static String localizedMessage(String code, String message) {
        if (message != null && message.codePoints().anyMatch(point ->
                Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN)) {
            return message;
        }
        String suffix = message != null && message.contains(": ")
                ? message.substring(message.indexOf(": ") + 2)
                : "";
        return switch (code) {
            case "UNAUTHORIZED" -> message != null && message.toLowerCase().contains("invalid")
                    ? "Token 无效、已过期或已撤销"
                    : "需要提供有效的 Bearer Token";
            case "FORBIDDEN" -> "当前身份没有执行此操作的权限";
            case "RESOURCE_NOT_FOUND" -> "未找到请求的资源";
            case "FIELD_REQUIRED", "MISSING_FIELD" ->
                    suffix.isBlank() ? "缺少必填字段" : "缺少必填字段：" + suffix;
            case "INVALID_FIELD" ->
                    suffix.isBlank() ? "请求字段格式不正确" : "请求字段格式不正确：" + suffix;
            case "INVALID_SUBJECT_TYPE" -> "主体类型只能是 USER 或 AGENT";
            case "INVALID_TOKEN_TTL" -> "Token 有效期不在允许范围内";
            case "INVALID_TTL" -> "有效期秒数必须在 1 到 7200 之间";
            case "INVALID_MAX_EVENTS" -> "最大事件数必须在 1 到 100000 之间";
            case "INVALID_PHASE" -> "执行阶段只能是调用前、正常返回后或异常抛出后";
            case "INVALID_EVENT_TIME" -> "事件时间必须使用 ISO-8601 格式";
            case "INVALID_SAMPLING_RATE" -> "采样率不在允许范围内";
            case "AGENT_COMMAND_STATE_CONFLICT" -> "Agent 命令状态已变化，请刷新后重试";
            case "FENCING_TOKEN_INVALID" -> "操作令牌无效或已过期，请重新发起操作";
            case "REDIS_UNAVAILABLE", "REDIS_FENCING_FAILED", "FENCING_SEQUENCE_FAILED" ->
                    "并发控制服务暂时不可用，请稍后重试";
            case "OPERATION_PLAN_INVALID_TRANSITION" -> "发布计划不允许执行当前状态变更";
            case "RESOURCE_VERSION_CONFLICT" -> "资源版本已变化，请刷新后重新操作";
            case "INVALID_RESOURCE_TYPE" -> "不支持此资源类型";
            case "INVALID_ROLLOUT_RESOURCE" -> "发布资源与所选应用或环境不匹配";
            case "INVALID_ROLLOUT_VERSION" -> "所选资源版本不存在或不可发布";
            case "INVALID_ENVIRONMENT" -> "环境不存在或不属于所选应用";
            case "INVALID_ENVIRONMENT_TYPE" -> "环境类型只能是 dev、sit、uat 或 prod";
            case "CAPABILITY_NOT_SUPPORTED" -> "目标 Agent 不支持该命令能力";
            default -> "请求处理失败（" + code + "）";
        };
    }
}
