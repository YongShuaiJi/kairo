package com.example.runtimemock.platform.api;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
final class IdempotencyFilter extends OncePerRequestFilter {

    private final JdbcTemplate jdbcTemplate;

    IdempotencyFilter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
            response.sendError(400, "Invalid Idempotency-Key");
            return;
        }
        byte[] requestBody = request.getInputStream().readAllBytes();
        String actor = header(request, "X-Actor", "system");
        String requestHash = PlatformJson.sha256(Map.of(
                "actor", actor,
                "method", request.getMethod(),
                "uri", request.getRequestURI(),
                "query", request.getQueryString() == null ? "" : request.getQueryString(),
                "body", new String(requestBody, StandardCharsets.UTF_8)));
        List<Map<String, Object>> existing = jdbcTemplate.queryForList("""
                select * from idempotency_record
                 where idempotency_key = ? and expires_at > current_timestamp
                """, key);
        if (!existing.isEmpty()) {
            Map<String, Object> record = existing.get(0);
            if (!actor.equals(String.valueOf(record.get("actor")))
                    || !requestHash.equals(String.valueOf(record.get("request_hash")))) {
                response.sendError(409, "Idempotency-Key was already used for a different request");
                return;
            }
            response.setStatus(((Number) record.get("response_status")).intValue());
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
            jdbcTemplate.update("""
                    insert into idempotency_record(
                        idempotency_key, actor, request_hash, response_status, response_json, created_at, expires_at
                    ) values (?, ?, ?, ?, ?, ?, ?)
                    """, key, actor, requestHash, wrappedResponse.getStatus(),
                    new String(responseBody, StandardCharsets.UTF_8),
                    Timestamp.from(now), Timestamp.from(now.plusSeconds(86_400)));
        }
        wrappedResponse.copyBodyToResponse();
    }

    private String header(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? fallback : value;
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
