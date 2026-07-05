package com.example.kairo.platform.service;

import java.util.Map;

public final class PlatformException extends RuntimeException {

    private final int status;
    private final String code;
    private final boolean retryable;
    private final Map<String, Object> details;

    private PlatformException(int status, String code, String message, boolean retryable,
                              Map<String, Object> details) {
        super(localizedMessage(code, message));
        this.status = status;
        this.code = code;
        this.retryable = retryable;
        this.details = details;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static PlatformException badRequest(String code, String message) {
        return new PlatformException(400, code, message, false, Map.of());
    }

    public static PlatformException methodNotAllowed(String code, String message) {
        return new PlatformException(405, code, message, false, Map.of());
    }

    public static PlatformException forbidden(String capability) {
        return new PlatformException(403, "FORBIDDEN",
                "当前身份缺少所需权限：" + capability, false, Map.of("capability", capability));
    }

    public static PlatformException unauthorized(String message) {
        return new PlatformException(401, "UNAUTHORIZED", message, false, Map.of());
    }

    public static PlatformException notFound(String resourceType, String resourceId) {
        return new PlatformException(404, "RESOURCE_NOT_FOUND",
                "未找到资源：" + resourceType + "（" + resourceId + "）", false,
                Map.of("resourceType", resourceType, "resourceId", resourceId));
    }

    public static PlatformException conflict(String code, String message, Map<String, Object> details) {
        return new PlatformException(409, code, message, true, details);
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
            default -> "请求处理失败（" + code + "）";
        };
    }
}
