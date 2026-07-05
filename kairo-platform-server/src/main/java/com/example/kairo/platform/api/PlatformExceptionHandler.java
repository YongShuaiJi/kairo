package com.example.kairo.platform.api;

import com.example.kairo.platform.service.PlatformException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
final class PlatformExceptionHandler {

    @ExceptionHandler(PlatformException.class)
    ResponseEntity<ApiError> platformException(PlatformException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(new ApiError(
                exception.code(),
                exception.getMessage(),
                correlationId(request),
                exception.details(),
                exception.retryable()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ApiError(
                "VALIDATION_FAILED",
                "请求参数校验失败",
                correlationId(request),
                Map.of("errorCount", exception.getErrorCount()),
                false
        ));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> notFound(NoResourceFoundException exception, HttpServletRequest request) {
        return ResponseEntity.status(404).body(new ApiError(
                "ROUTE_NOT_FOUND",
                "未找到 API 路由：" + exception.getResourcePath(),
                correlationId(request),
                Map.of(),
                false
        ));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        return ResponseEntity.internalServerError().body(new ApiError(
                "INTERNAL_ERROR",
                "服务器内部错误，请根据关联 ID 查看服务端日志",
                correlationId(request),
                Map.of(),
                false
        ));
    }

    private String correlationId(HttpServletRequest request) {
        String value = request.getHeader("X-Correlation-Id");
        return value == null ? "" : value;
    }
}
