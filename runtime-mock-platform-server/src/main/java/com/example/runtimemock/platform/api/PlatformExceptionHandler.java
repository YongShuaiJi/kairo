package com.example.runtimemock.platform.api;

import com.example.runtimemock.platform.service.PlatformException;
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
                "Request validation failed",
                correlationId(request),
                Map.of("errorCount", exception.getErrorCount()),
                false
        ));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> notFound(NoResourceFoundException exception, HttpServletRequest request) {
        return ResponseEntity.status(404).body(new ApiError(
                "ROUTE_NOT_FOUND",
                "API route not found: " + exception.getResourcePath(),
                correlationId(request),
                Map.of(),
                false
        ));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        return ResponseEntity.internalServerError().body(new ApiError(
                "INTERNAL_ERROR",
                exception.getClass().getName() + ": " + exception.getMessage(),
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
