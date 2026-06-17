package com.example.runtimemock.platform.api;

import com.example.runtimemock.platform.service.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public final class RequestContextFactory {

    public RequestContext from(HttpServletRequest request) {
        String actor = headerOrDefault(request, "X-Actor", "system");
        String correlationId = headerOrDefault(request, "X-Correlation-Id", "");
        String identitySource = headerOrDefault(request, "X-Identity-Source", "header-dev");
        String forwarded = request.getHeader("X-Forwarded-For");
        String ipAddress = forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr()
                : forwarded.split(",")[0].trim();
        return new RequestContext(actor, correlationId, ipAddress == null ? "" : ipAddress, identitySource);
    }

    private String headerOrDefault(HttpServletRequest request, String name, String defaultValue) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
