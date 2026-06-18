package com.example.runtimemock.platform.api;

import com.example.runtimemock.platform.service.RequestContext;
import com.example.runtimemock.platform.service.PlatformException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public final class RequestContextFactory {

    private final String sharedSecret;

    public RequestContextFactory(
            @Value("${runtime-mock.platform.auth.shared-secret:}") String sharedSecret) {
        this.sharedSecret = sharedSecret == null ? "" : sharedSecret;
    }

    public RequestContext from(HttpServletRequest request) {
        boolean loopback = isLoopback(request.getRemoteAddr());
        if (!loopback && !validSharedSecret(request)) {
            throw PlatformException.unauthorized(
                    "Remote platform requests require a valid bearer credential");
        }
        String actor = headerOrDefault(request, "X-Actor", "system");
        String correlationId = headerOrDefault(request, "X-Correlation-Id", "");
        String identitySource = headerOrDefault(request, "X-Identity-Source", "header-dev");
        String forwarded = loopback ? null : request.getHeader("X-Forwarded-For");
        String ipAddress = forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr()
                : forwarded.split(",")[0].trim();
        return new RequestContext(actor, correlationId, ipAddress == null ? "" : ipAddress, identitySource,
                headerOrDefault(request, "User-Agent", ""));
    }

    private boolean validSharedSecret(HttpServletRequest request) {
        if (sharedSecret.isBlank()) {
            return false;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        byte[] expected = sharedSecret.getBytes(StandardCharsets.UTF_8);
        byte[] actual = authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private boolean isLoopback(String address) {
        return "127.0.0.1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address)
                || "::1".equals(address);
    }

    private String headerOrDefault(HttpServletRequest request, String name, String defaultValue) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
