package com.example.kairo.platform.api;

import com.example.kairo.platform.auth.AccessTokenService;
import com.example.kairo.platform.auth.AuthProperties;
import com.example.kairo.platform.service.PlatformException;
import com.example.kairo.platform.service.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public final class RequestContextFactory {

    public static final String REQUEST_CONTEXT_ATTRIBUTE =
            RequestContextFactory.class.getName() + ".requestContext";

    private final AuthProperties properties;
    private final AccessTokenService accessTokenService;

    public RequestContextFactory(AuthProperties properties, AccessTokenService accessTokenService) {
        this.properties = properties;
        this.accessTokenService = accessTokenService;
    }

    public RequestContext from(HttpServletRequest request) {
        Object existing = request.getAttribute(REQUEST_CONTEXT_ATTRIBUTE);
        if (existing instanceof RequestContext context) {
            return context;
        }
        RequestContext context = "header-dev".equalsIgnoreCase(properties.getMode())
                ? fromDevelopmentHeaders(request)
                : fromBearerToken(request);
        request.setAttribute(REQUEST_CONTEXT_ATTRIBUTE, context);
        return context;
    }

    private RequestContext fromBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw PlatformException.unauthorized("Authorization: Bearer token is required");
        }
        AccessTokenService.TokenPrincipal principal =
                accessTokenService.authenticate(authorization.substring("Bearer ".length()).trim());
        return new RequestContext(
                principal.subjectId(),
                headerOrDefault(request, "X-Correlation-Id", ""),
                clientIp(request),
                principal.identitySource(),
                headerOrDefault(request, "User-Agent", "")
        );
    }

    private RequestContext fromDevelopmentHeaders(HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr())) {
            throw PlatformException.unauthorized("header-dev authentication is restricted to loopback requests");
        }
        return new RequestContext(
                headerOrDefault(request, "X-Actor", "system"),
                headerOrDefault(request, "X-Correlation-Id", ""),
                clientIp(request),
                headerOrDefault(request, "X-Identity-Source", "header-dev"),
                headerOrDefault(request, "User-Agent", "")
        );
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ipAddress = forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr()
                : forwarded.split(",")[0].trim();
        return ipAddress == null ? "" : ipAddress;
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
