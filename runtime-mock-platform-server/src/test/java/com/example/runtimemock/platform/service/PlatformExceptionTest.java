package com.example.runtimemock.platform.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformExceptionTest {

    @Test
    void localizesCommonBackendErrorsWithoutChangingMachineCodes() {
        PlatformException unauthorized = PlatformException.unauthorized(
                "Bearer token is invalid, expired, or revoked");
        PlatformException missingField = PlatformException.badRequest(
                "FIELD_REQUIRED", "Missing required field: applicationId");
        PlatformException forbidden = PlatformException.forbidden("RULE_MANAGE");

        assertThat(unauthorized.code()).isEqualTo("UNAUTHORIZED");
        assertThat(unauthorized.getMessage()).isEqualTo("Token 无效、已过期或已撤销");
        assertThat(missingField.code()).isEqualTo("FIELD_REQUIRED");
        assertThat(missingField.getMessage()).isEqualTo("缺少必填字段：applicationId");
        assertThat(forbidden.code()).isEqualTo("FORBIDDEN");
        assertThat(forbidden.getMessage()).isEqualTo("当前身份缺少所需权限：RULE_MANAGE");
    }

    @Test
    void preservesSpecificChineseBusinessMessages() {
        PlatformException exception = PlatformException.conflict(
                "OPERATION_PLAN_INVALID_TRANSITION",
                "发布计划当前状态不允许启动",
                java.util.Map.of());

        assertThat(exception.getMessage()).isEqualTo("发布计划当前状态不允许启动");
    }
}
