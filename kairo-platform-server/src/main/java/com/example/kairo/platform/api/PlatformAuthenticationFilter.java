package com.example.kairo.platform.api;

import com.example.kairo.platform.service.PlatformException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
final class PlatformAuthenticationFilter extends OncePerRequestFilter {

    private final RequestContextFactory requestContextFactory;

    PlatformAuthenticationFilter(RequestContextFactory requestContextFactory) {
        this.requestContextFactory = requestContextFactory;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/".equals(path)
                || "/api/v1".equals(path)
                || "/api/v1/control/health".equals(path)
                || path.startsWith("/actuator/health")
                // V1.6: OpenAPI contract + JSON schemas are publicly discoverable for AI/SDK clients;
                // the operations themselves still require authentication.
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || "/api/v1/schemas".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            var context = requestContextFactory.from(request);
            requireAllowedAgentRoute(request, context);
            filterChain.doFilter(request, response);
        } catch (PlatformException e) {
            response.setStatus(e.status());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"code":"%s","message":"%s","retryable":false}
                    """.formatted(json(e.code()), json(e.getMessage())).trim());
        }
    }

    private String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void requireAllowedAgentRoute(HttpServletRequest request,
                                          com.example.kairo.platform.service.RequestContext context) {
        if (!"agent".equals(context.identitySource())) {
            return;
        }
        String path = request.getRequestURI();
        String method = request.getMethod();
        boolean allowed = ("GET".equals(method) && "/api/v1/auth/me".equals(path))
                || ("POST".equals(method)
                && (("/api/v1/agents/" + context.actor() + "/heartbeat").equals(path)
                || ("/api/v1/agents/" + context.actor() + "/commands/next").equals(path)
                || path.matches("/api/v1/agent-commands/[^/]+/ack")));
        if (!allowed) {
            throw PlatformException.forbidden("AGENT_PROTOCOL_ONLY");
        }
    }
}
