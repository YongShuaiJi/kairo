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
        PlatformException forbidden = PlatformException.forbidden("APPROVE");

        assertThat(unauthorized.code()).isEqualTo("UNAUTHORIZED");
        assertThat(unauthorized.getMessage()).isEqualTo("Token 无效、已过期或已撤销");
        assertThat(missingField.code()).isEqualTo("FIELD_REQUIRED");
        assertThat(missingField.getMessage()).isEqualTo("缺少必填字段：applicationId");
        assertThat(forbidden.code()).isEqualTo("FORBIDDEN");
        assertThat(forbidden.getMessage()).isEqualTo("当前身份缺少所需权限：APPROVE");
    }

    @Test
    void preservesSpecificChineseBusinessMessages() {
        PlatformException exception = PlatformException.conflict(
                "APPROVAL_REQUIRED",
                "当前资源版本尚未获得有效审批，请先完成审批",
                java.util.Map.of());

        assertThat(exception.getMessage()).isEqualTo("当前资源版本尚未获得有效审批，请先完成审批");
    }
}
