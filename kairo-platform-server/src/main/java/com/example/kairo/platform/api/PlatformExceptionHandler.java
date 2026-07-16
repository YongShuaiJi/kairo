package com.example.kairo.platform.api;

import com.example.kairo.api.error.ApiError;
import com.example.kairo.api.error.ErrorCategory;
import com.example.kairo.api.error.ErrorTarget;
import com.example.kairo.api.error.KairoErrorCatalog;
import com.example.kairo.api.error.SuggestedAction;
import com.example.kairo.platform.service.PlatformException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/**
 * Maps every exception to the frozen V1.6 {@link ApiError} contract (&sect;2.4).
 * AI clients branch on {@code code}/{@code category}; the {@code message} is
 * human-readable and may be localised.
 */
@RestControllerAdvice
final class PlatformExceptionHandler {

    @ExceptionHandler(PlatformException.class)
    ResponseEntity<ApiError> platformException(PlatformException exception, HttpServletRequest request) {
        ApiError error = ApiError.of(exception.code(), exception.getMessage(),
                        exception.category(), exception.retryable())
                .withCorrelationId(correlationId(request))
                .withDetails(exception.details())
                .withSuggestedActions(exception.suggestedActions());
        if (exception.target() != null) {
            error = error.withTarget(exception.target());
        }
        return ResponseEntity.status(exception.status()).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<SuggestedAction> actions = List.of(
                SuggestedAction.safe("FIX_FIELD", "按 details.errors 修正请求字段后重试"));
        return ResponseEntity.badRequest().body(ApiError.of(
                "VALIDATION_FAILED", "请求参数校验失败", ErrorCategory.VALIDATION, false)
                .withCorrelationId(correlationId(request))
                .withSuggestedActions(actions));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> notFound(NoResourceFoundException exception, HttpServletRequest request) {
        return ResponseEntity.status(404).body(ApiError.of(
                "ROUTE_NOT_FOUND", "未找到 API 路由：" + exception.getResourcePath(),
                ErrorCategory.NOT_FOUND, false)
                .withCorrelationId(correlationId(request))
                .withTarget(ErrorTarget.bodyPath(exception.getResourcePath())));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        // V1.7 M0: resolve INTERNAL_ERROR from the authoritative catalog (handler validates).
        KairoErrorCatalog.Entry internal = KairoErrorCatalog.require("INTERNAL_ERROR");
        return ResponseEntity.internalServerError().body(ApiError.of(
                "INTERNAL_ERROR", "服务器内部错误，请根据关联 ID 查看服务端日志",
                internal.category(), internal.retryable())
                .withCorrelationId(correlationId(request))
                .withSuggestedActions(List.of(
                        SuggestedAction.manual("CONTACT_OPERATOR", "携带 correlationId 联系运维"))));
    }

    private String correlationId(HttpServletRequest request) {
        String value = request.getHeader("X-Correlation-Id");
        return value == null ? "" : value;
    }
}
