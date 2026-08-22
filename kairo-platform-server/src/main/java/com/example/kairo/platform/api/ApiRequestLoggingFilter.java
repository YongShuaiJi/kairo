package com.example.kairo.platform.api;

import com.example.kairo.api.diagnostics.DiagnosticEvent;
import com.example.kairo.platform.service.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * One diagnostic boundary for every Platform HTTP feature.
 *
 * <p>Successful reads are DEBUG to avoid health/read traffic flooding production logs. Mutations
 * and failures are retained at INFO/WARN/ERROR. Request bodies, queries and credentials are never
 * logged; domain-specific services log their durable identifiers separately.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
final class ApiRequestLoggingFilter extends OncePerRequestFilter {

    static final String CORRELATION_ID_ATTRIBUTE =
            ApiRequestLoggingFilter.class.getName() + ".correlationId";
    private static final Logger LOG = LoggerFactory.getLogger(ApiRequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = correlationId(request);
        request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId);
        response.setHeader("X-Correlation-Id", correlationId);
        long started = System.nanoTime();
        Throwable failure = null;
        MDC.put("correlationId", correlationId);
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            long durationMillis = (System.nanoTime() - started) / 1_000_000L;
            int status = response.getStatus();
            String message = DiagnosticEvent.format("http.request.completed",
                    "correlationId", correlationId,
                    "method", request.getMethod(),
                    "path", request.getRequestURI(),
                    "status", status,
                    "durationMs", durationMillis,
                    "actor", actor(request),
                    "identitySource", identitySource(request),
                    "failure", DiagnosticEvent.failureSummary(failure),
                    "failureStack", DiagnosticEvent.stackSummary(failure));
            if (failure != null || status >= 500) {
                LOG.error(message);
            } else if (status >= 400) {
                LOG.warn(message);
            } else if (isMutation(request.getMethod())) {
                LOG.info(message);
            } else {
                LOG.debug(message);
            }
            MDC.remove("correlationId");
        }
    }

    private static boolean isMutation(String method) {
        return "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method);
    }

    private static String correlationId(HttpServletRequest request) {
        String supplied = request.getHeader("X-Correlation-Id");
        if (supplied != null && supplied.length() <= 128
                && supplied.matches("[A-Za-z0-9._:-]+")) {
            return supplied;
        }
        return UUID.randomUUID().toString();
    }

    private static String actor(HttpServletRequest request) {
        Object value = request.getAttribute(RequestContextFactory.REQUEST_CONTEXT_ATTRIBUTE);
        return value instanceof RequestContext context ? context.actor() : "anonymous";
    }

    private static String identitySource(HttpServletRequest request) {
        Object value = request.getAttribute(RequestContextFactory.REQUEST_CONTEXT_ATTRIBUTE);
        return value instanceof RequestContext context ? context.identitySource() : "unauthenticated";
    }
}
