package com.example.kairo.platform.api;

import com.example.kairo.api.diagnostics.DiagnosticEvent;
import com.example.kairo.platform.persistence.mapper.IdempotencyRecordMapper;
import com.example.kairo.platform.service.PlatformJson;
import com.example.kairo.platform.service.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * V1.6 acceptance safety: a database-safe idempotency reservation/in-progress/completed protocol
 * that works across Platform nodes and never executes a mutation twice <em>within the fenced
 * reservation and the Platform transaction boundary</em>.
 *
 * <p>On a write with an {@code Idempotency-Key}:
 * <ol>
 *   <li><b>Reserve</b> &mdash; atomically {@code INSERT} a row in {@code IN_PROGRESS} state stamped
 *       with a unique {@code ownerToken} (PK collision => another node owns it). The owner
 *       executes the request.</li>
 *   <li><b>Heartbeat</b> &mdash; while the owner's request is executing, a daemon renews the lease
 *       (fenced by {@code ownerToken}) so a <em>live</em> owner is never reclaimed, no matter how
 *       long the request takes. The heartbeat is stopped in {@code finally}.</li>
 *   <li><b>Complete</b> &mdash; on a 2xx-4xx response, cache the result as {@code COMPLETED} so
 *       replays return it verbatim. On a 5xx (or owner failure), <em>release</em> the reservation
 *       so a retry can re-execute; a 5xx is never cached as success.</li>
 *   <li><b>Wait &amp; replay</b> &mdash; a same-key same-request (same actor + hash) waiter polls
 *       until the owner completes, then replays the cached result without executing.</li>
 *   <li><b>Conflict</b> &mdash; a same-key different-actor/body request returns
 *       {@code 409 IDEMPOTENCY_KEY_CONFLICT}. A live owner whose request exceeds the wait window
 *       returns {@code 409 IDEMPOTENCY_KEY_IN_PROGRESS} (the owner is not reclaimed).</li>
 *   <li><b>Recover</b> &mdash; if an owner dies (its lease expires because the heartbeat stopped),
 *       a same-request waiter reclaims the reservation (transferring the owner token) and executes.</li>
 *   <li><b>Reuse</b> &mdash; a row past its overall {@code expires_at} TTL is removed so its primary
 *       key can be reused instead of colliding forever.</li>
 * </ol>
 *
 * <p><b>Distributed-guarantee boundary (honest scope).</b> Exactly-once execution is guaranteed for
 * the fenced reservation state and for any side effect committed inside the same Platform/database
 * transaction as the handler. It is <em>not</em> a mathematically-impossible global exactly-once for
 * side effects performed outside that boundary (e.g. an email sent, an external HTTP call made) by a
 * handler that then crashes before completing: a recovered owner will re-execute and such a side
 * effect may be observed twice. Callables that cannot tolerate this must perform their external
 * side effects transactionally or via an idempotent consumer. The {@code ownerToken} fencing
 * ensures a stale owner can never corrupt the reservation (complete/delete the new owner's row),
 * but it cannot un-execute a side effect already performed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
final class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyFilter.class);

    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private final IdempotencyRecordMapper idempotencyRecordMapper;
    private final long leaseMillis;
    private final long renewMillis;
    private final long maxWaitMillis;
    private final long pollMillis;
    private final ScheduledExecutorService heartbeat;

    IdempotencyFilter(IdempotencyRecordMapper idempotencyRecordMapper,
                      @Value("${kairo.platform.idempotency.lease-millis:30000}") long leaseMillis,
                      @Value("${kairo.platform.idempotency.renew-millis:10000}") long renewMillis,
                      @Value("${kairo.platform.idempotency.max-wait-millis:35000}") long maxWaitMillis,
                      @Value("${kairo.platform.idempotency.poll-millis:25}") long pollMillis) {
        this.idempotencyRecordMapper = idempotencyRecordMapper;
        this.leaseMillis = Math.max(1L, leaseMillis);
        // The heartbeat must renew strictly before the lease can expire, else a live owner could be
        // reclaimed between renewals. Clamp the effective interval to at most half the lease.
        this.renewMillis = Math.max(1L, Math.min(renewMillis, this.leaseMillis / 2L));
        this.maxWaitMillis = Math.max(this.leaseMillis + 1L, maxWaitMillis);
        this.pollMillis = Math.max(1L, pollMillis);
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kairo-idempotency-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
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
        String correlationId = correlationId(request);
        if (key.isBlank() || key.length() > 255) {
            writeError(response, 400, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key 不能为空且长度不能超过 255 个字符",
                    com.example.kairo.api.error.ErrorCategory.VALIDATION, correlationId);
            return;
        }
        byte[] requestBody = request.getInputStream().readAllBytes();
        Object contextValue = request.getAttribute(RequestContextFactory.REQUEST_CONTEXT_ATTRIBUTE);
        String actor = contextValue instanceof RequestContext context
                ? context.actor()
                : "anonymous";
        String requestHash = PlatformJson.sha256(Map.of(
                "actor", actor,
                "method", request.getMethod(),
                "uri", request.getRequestURI(),
                "query", request.getQueryString() == null ? "" : request.getQueryString(),
                "body", new String(requestBody, StandardCharsets.UTF_8)));

        Instant now = Instant.now();
        String ownerToken = newOwnerToken();
        Timestamp leaseExpiresAt = Timestamp.from(now.plusMillis(leaseMillis));
        Timestamp createdAt = Timestamp.from(now);
        Timestamp expiresAt = Timestamp.from(now.plusSeconds(86_400));

        if (reserve(key, actor, requestHash, ownerToken, leaseExpiresAt, createdAt, expiresAt)) {
            logAction("idempotency.reserved", key, correlationId, actor, "OWNER", -1);
            executeOwned(key, ownerToken, new CachedBodyRequest(request, requestBody),
                    new ContentCachingResponseWrapper(response), filterChain, correlationId);
            return;
        }
        handleExisting(key, actor, requestHash, ownerToken, request, requestBody, response, filterChain,
                createdAt, leaseExpiresAt, expiresAt, correlationId);
    }

    private static String newOwnerToken() {
        return UUID.randomUUID().toString();
    }

    /** Atomically reserve the key, stamping the owner's fencing token. Returns true when this node owns it. */
    private boolean reserve(String key, String actor, String hash, String ownerToken,
                            Timestamp leaseExpiresAt, Timestamp createdAt, Timestamp expiresAt) {
        try {
            idempotencyRecordMapper.insertReservation(key, actor, hash, STATUS_IN_PROGRESS,
                    ownerToken, leaseExpiresAt, createdAt, expiresAt);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * The owner executes the request with a live heartbeat renewing its lease, then completes
     * (2xx-4xx) or releases (5xx/failure). The heartbeat is always stopped in {@code finally}.
     */
    private void executeOwned(String key, String ownerToken, CachedBodyRequest wrappedRequest,
                              ContentCachingResponseWrapper wrappedResponse, FilterChain chain,
                              String correlationId)
            throws ServletException, IOException {
        ScheduledFuture<?> renewal = startHeartbeat(key, ownerToken, correlationId);
        try {
            chain.doFilter(wrappedRequest, wrappedResponse);
            int status = wrappedResponse.getStatus();
            byte[] body = wrappedResponse.getContentAsByteArray();
            if (status >= 200 && status < 500) {
                int updated = idempotencyRecordMapper.completeRecord(key, ownerToken, status,
                        new String(body, StandardCharsets.UTF_8), Timestamp.from(Instant.now()));
                logAction("idempotency.completed", key, correlationId, "", "OWNER", status);
                if (updated == 0) {
                    LOG.warn(DiagnosticEvent.format("idempotency.fence_lost",
                            "keyHash", DiagnosticEvent.fingerprint(key),
                            "correlationId", correlationId, "phase", "COMPLETE"));
                }
            } else {
                // 5xx: never cache as success; release so a retry can re-execute.
                idempotencyRecordMapper.deleteReservation(key, ownerToken);
                logAction("idempotency.released", key, correlationId, "", "HTTP_5XX", status);
            }
            wrappedResponse.copyBodyToResponse();
        } catch (RuntimeException | ServletException | IOException e) {
            // Owner failed before producing a response: release the reservation (fenced by ownerToken).
            try {
                idempotencyRecordMapper.deleteReservation(key, ownerToken);
            } catch (RuntimeException ignored) {
                // Best-effort cleanup; propagate the original failure.
            }
            LOG.error(DiagnosticEvent.format("idempotency.owner_failed",
                    "keyHash", DiagnosticEvent.fingerprint(key),
                    "correlationId", correlationId,
                    "failure", DiagnosticEvent.failureSummary(e),
                    "failureStack", DiagnosticEvent.stackSummary(e)));
            throw e;
        } finally {
            if (renewal != null) {
                renewal.cancel(false);
            }
        }
    }

    /** Renew the lease while the owner is still executing, fenced by the owner token. */
    private ScheduledFuture<?> startHeartbeat(String key, String ownerToken, String correlationId) {
        AtomicBoolean failureReported = new AtomicBoolean();
        try {
            return heartbeat.scheduleAtFixedRate(() -> {
                try {
                    int updated = idempotencyRecordMapper.renewLease(key, ownerToken,
                            Timestamp.from(Instant.now().plusMillis(leaseMillis)));
                    if (updated == 0 && failureReported.compareAndSet(false, true)) {
                        LOG.warn(DiagnosticEvent.format("idempotency.lease_not_renewed",
                                "keyHash", DiagnosticEvent.fingerprint(key),
                                "correlationId", correlationId, "reason", "FENCE_NOT_OWNED"));
                    } else if (updated > 0) {
                        failureReported.set(false);
                    }
                } catch (RuntimeException failure) {
                    if (failureReported.compareAndSet(false, true)) {
                        LOG.warn(DiagnosticEvent.format("idempotency.lease_renewal_failed",
                                "keyHash", DiagnosticEvent.fingerprint(key),
                                "correlationId", correlationId,
                                "failure", DiagnosticEvent.failureSummary(failure),
                                "failureStack", DiagnosticEvent.stackSummary(failure)));
                    }
                }
            }, renewMillis, renewMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException failure) {
            LOG.warn(DiagnosticEvent.format("idempotency.heartbeat_start_failed",
                    "keyHash", DiagnosticEvent.fingerprint(key),
                    "correlationId", correlationId,
                    "failure", DiagnosticEvent.failureSummary(failure),
                    "failureStack", DiagnosticEvent.stackSummary(failure)));
            return null; // If the executor is shut down, the owner still runs without renewal.
        }
    }

    private void handleExisting(String key, String actor, String hash, String ownerToken,
                                HttpServletRequest request, byte[] requestBody,
                                HttpServletResponse response, FilterChain chain,
                                Timestamp createdAt, Timestamp leaseExpiresAt, Timestamp expiresAt,
                                String correlationId)
            throws ServletException, IOException {
        Map<String, Object> record = idempotencyRecordMapper.findRecord(key);
        if (record == null) {
            // The colliding row is past its overall expires_at TTL (findRecord filters those out)
            // or has vanished. Race-safely remove the expired row so the key can be reused, then
            // retry the reservation.
            idempotencyRecordMapper.deleteExpired(key, Timestamp.from(Instant.now()));
            if (reserve(key, actor, hash, ownerToken, leaseExpiresAt, createdAt, expiresAt)) {
                executeOwned(key, ownerToken, new CachedBodyRequest(request, requestBody),
                        new ContentCachingResponseWrapper(response), chain, correlationId);
                return;
            }
            // Another node reserved a fresh row in the gap, or a live IN_PROGRESS row reappeared.
            record = idempotencyRecordMapper.findRecord(key);
            if (record == null) {
                writeConflict(response, correlationId);
                return;
            }
        }

        String status = String.valueOf(record.get("status"));
        String recordActor = String.valueOf(record.get("actor"));
        String recordHash = String.valueOf(record.get("request_hash"));

        if (STATUS_COMPLETED.equals(status)) {
            if (actor.equals(recordActor) && hash.equals(recordHash)) {
                replay(response, record, key, correlationId);
            } else {
                writeConflict(response, correlationId);
            }
            return;
        }

        // IN_PROGRESS: a different actor/body for the same key is a conflict.
        if (!actor.equals(recordActor) || !hash.equals(recordHash)) {
            writeConflict(response, correlationId);
            return;
        }
        // Same-key same-request: wait for the owner to complete, then replay.
        Map<String, Object> completed = waitForCompletion(key);
        if (completed != null && STATUS_COMPLETED.equals(String.valueOf(completed.get("status")))) {
            replay(response, completed, key, correlationId);
            return;
        }
        // The owner is either dead (lease expired) or alive-but-slow (wait window elapsed).
        // Re-check the live state before deciding: never reclaim a live owner.
        Map<String, Object> current = idempotencyRecordMapper.findRecord(key);
        if (current == null) {
            // Vanished (owner released) or expired TTL: clean up and reserve fresh.
            idempotencyRecordMapper.deleteExpired(key, Timestamp.from(Instant.now()));
            if (reserve(key, actor, hash, ownerToken, leaseExpiresAt, createdAt, expiresAt)) {
                executeOwned(key, ownerToken, new CachedBodyRequest(request, requestBody),
                        new ContentCachingResponseWrapper(response), chain, correlationId);
                return;
            }
            writeConflict(response, correlationId);
            return;
        }
        if (STATUS_COMPLETED.equals(String.valueOf(current.get("status")))) {
            if (actor.equals(String.valueOf(current.get("actor")))
                    && hash.equals(String.valueOf(current.get("request_hash")))) {
                replay(response, current, key, correlationId);
            } else {
                writeConflict(response, correlationId);
            }
            return;
        }
        if (isLeaseExpired(current)) {
            // Owner died (heartbeat stopped and lease elapsed): reclaim and execute.
            Timestamp nowTs = Timestamp.from(Instant.now());
            Timestamp newLease = Timestamp.from(Instant.now().plusMillis(leaseMillis));
            if (idempotencyRecordMapper.reclaimReservation(key, actor, hash, ownerToken, newLease, nowTs) > 0) {
                logAction("idempotency.reclaimed", key, correlationId, actor, "EXPIRED_OWNER", -1);
                executeOwned(key, ownerToken, new CachedBodyRequest(request, requestBody),
                        new ContentCachingResponseWrapper(response), chain, correlationId);
                return;
            }
            // Another waiter reclaimed/completed first; re-read and replay or conflict.
            Map<String, Object> reread = idempotencyRecordMapper.findRecord(key);
            if (reread != null && STATUS_COMPLETED.equals(String.valueOf(reread.get("status")))
                    && actor.equals(String.valueOf(reread.get("actor")))
                    && hash.equals(String.valueOf(reread.get("request_hash")))) {
                replay(response, reread, key, correlationId);
            } else {
                writeConflict(response, correlationId);
            }
            return;
        }
        // The owner is alive (lease still live) but the wait window elapsed: do NOT reclaim.
        writeStillProcessing(response, correlationId);
    }

    /** A live owner's lease has expired only if its {@code lease_expires_at} is in the past. */
    private static boolean isLeaseExpired(Map<String, Object> record) {
        Object lease = record.get("lease_expires_at");
        if (!(lease instanceof Timestamp t)) {
            return true; // No lease on an IN_PROGRESS row is treated as expired (recover).
        }
        return t.toInstant().isBefore(Instant.now());
    }

    /**
     * Poll until the owner completes (return the record), the record vanishes (return null), the
     * lease expires (return null so the caller reclaims a dead owner), or the wait window elapses
     * (return null; the caller re-checks the lease and refuses to reclaim a live owner).
     */
    private Map<String, Object> waitForCompletion(String key) {
        long deadline = System.currentTimeMillis() + maxWaitMillis;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> record = idempotencyRecordMapper.findRecord(key);
            if (record == null) {
                return null;
            }
            if (STATUS_COMPLETED.equals(String.valueOf(record.get("status")))) {
                return record;
            }
            if (isLeaseExpired(record)) {
                return null; // dead owner -> caller reclaims
            }
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private void replay(HttpServletResponse response, Map<String, Object> record,
                        String key, String correlationId) throws IOException {
        int status = ((Number) record.get("response_status")).intValue();
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write(String.valueOf(record.get("response_json")));
        logAction("idempotency.replayed", key, correlationId, "", "COMPLETED", status);
    }

    private static void logAction(String event, String key, String correlationId,
                                  String actor, String outcome, int status) {
        LOG.info(DiagnosticEvent.format(event,
                "keyHash", DiagnosticEvent.fingerprint(key),
                "correlationId", correlationId,
                "actor", actor,
                "outcome", outcome,
                "status", status));
    }

    private static String correlationId(HttpServletRequest request) {
        Object generated = request.getAttribute(ApiRequestLoggingFilter.CORRELATION_ID_ATTRIBUTE);
        return generated instanceof String value && !value.isBlank()
                ? value : headerOrDefault(request, "X-Correlation-Id", "");
    }

    private void writeConflict(HttpServletResponse response, String correlationId) throws IOException {
        writeError(response, 409, "IDEMPOTENCY_KEY_CONFLICT",
                "该 Idempotency-Key 已用于其他请求，请更换后重试",
                com.example.kairo.api.error.ErrorCategory.CONFLICT, correlationId);
    }

    private void writeStillProcessing(HttpServletResponse response, String correlationId) throws IOException {
        com.example.kairo.api.error.ApiError error = com.example.kairo.api.error.ApiError.of(
                "IDEMPOTENCY_KEY_IN_PROGRESS",
                "该 Idempotency-Key 对应的请求仍在处理中，请稍后重试",
                com.example.kairo.api.error.ErrorCategory.CONFLICT, true)
                .withCorrelationId(correlationId == null ? "" : correlationId);
        response.setStatus(409);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write(PlatformJson.write(error));
    }

    private void writeError(HttpServletResponse response, int status, String code, String message,
                            com.example.kairo.api.error.ErrorCategory category, String correlationId)
            throws IOException {
        com.example.kairo.api.error.ApiError error = com.example.kairo.api.error.ApiError.of(
                code, message, category, false)
                .withCorrelationId(correlationId == null ? "" : correlationId);
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write(PlatformJson.write(error));
    }

    private static String headerOrDefault(HttpServletRequest request, String name, String defaultValue) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    @Override
    public void destroy() {
        heartbeat.shutdownNow();
        super.destroy();
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        byte[] getBody() {
            return body;
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
