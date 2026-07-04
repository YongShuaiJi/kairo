package com.example.runtimemock.platform.api;

import com.example.runtimemock.platform.persistence.mapper.IdempotencyRecordMapper;
import com.example.runtimemock.platform.service.PlatformJson;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
final class IdempotencyFilter extends OncePerRequestFilter {

    private final IdempotencyRecordMapper idempotencyRecordMapper;

    IdempotencyFilter(IdempotencyRecordMapper idempotencyRecordMapper) {
        this.idempotencyRecordMapper = idempotencyRecordMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        return !("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)
                || "DELETE".equals(method))
                || request.getHeader("Idempotency-Key") == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String key = request.getHeader("Idempotency-Key");
        if (key.isBlank() || key.length() > 255) {
            writeError(response, 400, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key 不能为空且长度不能超过 255 个字符");
            return;
        }
        byte[] requestBody = request.getInputStream().readAllBytes();
        Object contextValue = request.getAttribute(RequestContextFactory.REQUEST_CONTEXT_ATTRIBUTE);
        String actor = contextValue instanceof com.example.runtimemock.platform.service.RequestContext context
                ? context.actor()
                : "anonymous";
        String requestHash = PlatformJson.sha256(Map.of(
                "actor", actor,
                "method", request.getMethod(),
                "uri", request.getRequestURI(),
                "query", request.getQueryString() == null ? "" : request.getQueryString(),
                "body", new String(requestBody, StandardCharsets.UTF_8)));
        Map<String, Object> record = idempotencyRecordMapper.activeRecord(key);
        if (record != null && !record.isEmpty()) {
            if (!actor.equals(String.valueOf(record.get("actor")))
                    || !requestHash.equals(String.valueOf(record.get("request_hash")))) {
                writeError(response, 409, "IDEMPOTENCY_KEY_CONFLICT",
                        "该 Idempotency-Key 已用于其他请求，请更换后重试");
                return;
            }
            response.setStatus(((Number) record.get("response_status")).intValue());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json");
            response.getWriter().write(String.valueOf(record.get("response_json")));
            return;
        }

        CachedBodyRequest wrappedRequest = new CachedBodyRequest(request, requestBody);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(wrappedRequest, wrappedResponse);
        byte[] responseBody = wrappedResponse.getContentAsByteArray();
        if (wrappedResponse.getStatus() >= 200 && wrappedResponse.getStatus() < 500) {
            Instant now = Instant.now();
            idempotencyRecordMapper.insertRecord(key, actor, requestHash, wrappedResponse.getStatus(),
                    new String(responseBody, StandardCharsets.UTF_8),
                    Timestamp.from(now), Timestamp.from(now.plusSeconds(86_400)));
        }
        wrappedResponse.copyBodyToResponse();
    }

    private void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("""
                {"code":"%s","message":"%s","retryable":false}
                """.formatted(code, message).trim());
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // Synchronous request processing only.
                }

                @Override
                public int read() {
                    return input.read();
                }
            };
        }
    }
}
