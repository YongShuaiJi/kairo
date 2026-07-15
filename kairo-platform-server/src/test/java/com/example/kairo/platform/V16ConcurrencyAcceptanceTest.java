package com.example.kairo.platform;

import com.example.kairo.platform.persistence.mapper.TestPlatformMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.6 acceptance safety: <em>truly concurrent</em> proofs for the database-safe idempotency
 * reservation protocol and the per-token automation-session limit. These tests run against a real
 * embedded server ({@link SpringBootTest.WebEnvironment#RANDOM_PORT}) with concurrent HTTP
 * requests released simultaneously by a {@link CountDownLatch}, because {@code MockMvc} is not
 * documented as thread-safe for concurrent {@code perform} calls.
 *
 * <p>Covers (Codex review): exactly-once execution under concurrent same-key same-request;
 * conflict (409) + no double execution under concurrent same-key different-request; recovery
 * semantics (5xx/owner failure releases the reservation so a retry re-executes; an expired lease
 * is reclaimed and re-executed rather than hanging); and the per-token {@code maxSessions=1}
 * limit holding under true concurrency (exactly one session, the rest 409).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v16_concurrency;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "kairo.platform.auth.mode=local-token",
        "kairo.platform.auth.bootstrap-token=bootstrap-test-token",
        "kairo.platform.rollout.scheduler.enabled=false",
        "kairo.platform.script.command-timeout-ms=5000",
        "kairo.platform.script.expiry.initial-delay-ms=999999",
        "kairo.platform.script.expiry.fixed-delay-ms=999999",
        "kairo.platform.automation.expiry.initial-delay-ms=999999",
        "kairo.platform.automation.expiry.fixed-delay-ms=999999"
})
@ActiveProfiles("test")
class V16ConcurrencyAcceptanceTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestPlatformMapper fixtures;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        fixtures.ensureDefaultProject();
        fixtures.ensureDefaultApplication();
        fixtures.ensureDefaultEnvironment();
    }

    private String base() {
        return "http://localhost:" + port + "/api/v1";
    }

    /** Issue an AI token (RULE_MANAGE-scoped unless overridden) with the given maxSessions. */
    private String issueToken(String username, String scopeJson, String maxSessions) throws Exception {
        StringBuilder body = new StringBuilder("{\"username\":\"" + username + "\",\"ttlSeconds\":3600");
        body.append(",\"scope\":").append(scopeJson != null ? scopeJson : "[\"RULE_MANAGE\"]");
        body.append(",\"source\":\"mcp\"");
        if (maxSessions != null) {
            body.append(",\"maxSessions\":").append(maxSessions);
        }
        body.append("}");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth("bootstrap-test-token");
        ResponseEntity<String> resp = rest.postForEntity(base() + "/auth/tokens",
                new HttpEntity<>(body.toString(), h), String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).as("token issue: %s", resp.getBody()).isTrue();
        return mapper.readTree(resp.getBody()).get("token").asText();
    }

    private String createSessionBody(String caller) {
        return "{\"caller\":\"" + caller + "\",\"source\":\"mcp\",\"applicationId\":\"app-default\","
                + "\"environmentId\":\"env-default\",\"requestedCapabilityProfile\":\"SAFE\",\"ttlMillis\":600000}";
    }

    private ResponseEntity<String> postWithIdempotency(String token, String key, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        h.set("Idempotency-Key", key);
        return rest.postForEntity(base() + "/automation-sessions", new HttpEntity<>(body, h), String.class);
    }

    private ResponseEntity<String> postCreate(String token, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return rest.postForEntity(base() + "/automation-sessions", new HttpEntity<>(body, h), String.class);
    }

    private HttpHeaders jsonHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }

    private int sessionCountForCaller(String caller) {
        Integer c = jdbc.queryForObject(
                "select count(*) from automation_session where caller = ?", Integer.class, caller);
        return c == null ? 0 : c;
    }

    private String sessionId(ResponseEntity<String> resp) throws Exception {
        JsonNode node = mapper.readTree(resp.getBody());
        return node.has("sessionId") ? node.get("sessionId").asText() : null;
    }

    // -------------------------------------------------------- exactly-once under concurrency

    @Test
    void concurrentSameKeyIdempotencyExecutesExactlyOnce() throws Exception {
        String token = issueToken("ai-once-" + System.nanoTime(), null, null);
        String caller = "ai-once-sess-" + System.nanoTime();
        String body = createSessionBody(caller);
        String key = "idem-once-" + System.nanoTime();
        int n = 8;

        List<ResponseEntity<String>> responses = fireConcurrently(n,
                () -> postWithIdempotency(token, key, body));

        // Every concurrent same-key same-request returns 201 with the SAME sessionId (replayed).
        Set<String> sessionIds = new HashSet<>();
        for (ResponseEntity<String> r : responses) {
            assertThat(r.getStatusCode().value()).as("status: %s", r.getBody()).isEqualTo(201);
            sessionIds.add(sessionId(r));
        }
        assertThat(sessionIds).as("all replays share one sessionId").hasSize(1);
        // The mutation executed exactly once: one session row for the caller.
        assertThat(sessionCountForCaller(caller))
                .as("exactly-once: a single session row despite %d concurrent requests", n)
                .isEqualTo(1);
    }

    // -------------------------------------------------------- conflict + no double execution under concurrency

    @Test
    void concurrentSameKeyDifferentBodyConflictsWithoutDoubleExecution() throws Exception {
        String token = issueToken("ai-conf-" + System.nanoTime(), null, null);
        String caller1 = "ai-conf-sess-1-" + System.nanoTime();
        String caller2 = "ai-conf-sess-2-" + System.nanoTime();
        String body1 = createSessionBody(caller1);
        String body2 = createSessionBody(caller2);
        String key = "idem-conf-" + System.nanoTime();
        int perGroup = 4;

        // Half the threads use body1, half use body2, all under the SAME idempotency key.
        List<ResponseEntity<String>> responses = new ArrayList<>();
        responses.addAll(fireConcurrently(perGroup, () -> postWithIdempotency(token, key, body1)));
        responses.addAll(fireConcurrently(perGroup, () -> postWithIdempotency(token, key, body2)));

        int created = 0, conflicts = 0;
        Set<String> createdIds = new HashSet<>();
        for (ResponseEntity<String> r : responses) {
            int sc = r.getStatusCode().value();
            if (sc == 201) {
                created++;
                createdIds.add(sessionId(r));
            } else if (sc == 409) {
                conflicts++;
                assertThat(mapper.readTree(r.getBody()).get("code").asText())
                        .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
            } else {
                throw new AssertionError("unexpected status " + sc + ": " + r.getBody());
            }
        }
        // Exactly one body wins and is executed once; the other body conflicts every time.
        assertThat(created).as("exactly one winner body executed").isEqualTo(perGroup);
        assertThat(conflicts).as("the other body conflicts every time").isEqualTo(perGroup);
        assertThat(createdIds).as("the winner's replays share one sessionId").hasSize(1);
        // No double execution: exactly one session row across both bodies.
        assertThat(sessionCountForCaller(caller1) + sessionCountForCaller(caller2))
                .as("exactly one session row total (no double execution)").isEqualTo(1);
    }

    // -------------------------------------------------------- recovery: 5xx releases, retry re-executes

    @Test
    void failedRequestReleasesReservationSoRetryReExecutes() throws Exception {
        String token = issueToken("ai-5xx-" + System.nanoTime(), null, null);
        String caller = "ai-5xx-sess-" + System.nanoTime();
        String key = "idem-5xx-" + System.nanoTime();

        // A malformed JSON body triggers HttpMessageNotReadableException, which is not specifically
        // handled and maps to 500 INTERNAL_ERROR. The reservation must be RELEASED (not cached as
        // success), so a 5xx is never replayed as a success.
        ResponseEntity<String> failed = postWithIdempotency(token, key, "{invalid json");
        assertThat(failed.getStatusCode().value())
                .as("malformed body must surface as 5xx (not cached): %s", failed.getBody())
                .isEqualTo(500);
        // The reservation was released: no completed row lingers.
        Integer completed = jdbc.queryForObject(
                "select count(*) from idempotency_record where idempotency_key = ? and status = 'COMPLETED'",
                Integer.class, key);
        assertThat(completed).as("5xx must not be cached as a completed result").isEqualTo(0);

        // Retry with the SAME key but a VALID body: it must re-reserve and re-execute (201), NOT
        // return 409 IDEMPOTENCY_KEY_CONFLICT (which is what a wrongly-cached 5xx would produce).
        ResponseEntity<String> retry = postWithIdempotency(token, key, createSessionBody(caller));
        assertThat(retry.getStatusCode().value())
                .as("retry after released 5xx must re-execute (201), not conflict (409): %s", retry.getBody())
                .isEqualTo(201);
        assertThat(sessionCountForCaller(caller))
                .as("the valid retry created exactly one session").isEqualTo(1);
    }

    // -------------------------------------------------------- recovery: expired lease reclaimed + re-executed

    @Test
    void expiredLeaseIsReclaimedAndReExecuted() throws Exception {
        String token = issueToken("ai-reclaim-" + System.nanoTime(), null, null);
        String caller = "ai-reclaim-sess-" + System.nanoTime();
        String body = createSessionBody(caller);
        String key = "idem-reclaim-" + System.nanoTime();

        // 1. A real request completes normally; its reservation becomes COMPLETED.
        ResponseEntity<String> first = postWithIdempotency(token, key, body);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        String firstSessionId = sessionId(first);

        // 2. Simulate a dead owner: revert the reservation to IN_PROGRESS with an expired lease
        //    (the owner reserved but never completed and its lease elapsed).
        jdbc.update("update idempotency_record set status = 'IN_PROGRESS', "
                + "lease_expires_at = ? where idempotency_key = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), key);

        // 3. A same-key same-request waiter arrives: the expired lease is reclaimed and the request
        //    re-executes (201 with a NEW sessionId), rather than hanging or conflicting.
        ResponseEntity<String> reclaimed = postWithIdempotency(token, key, body);
        assertThat(reclaimed.getStatusCode().value())
                .as("reclaimed request must re-execute (201): %s", reclaimed.getBody())
                .isEqualTo(201);
        assertThat(sessionId(reclaimed))
                .as("reclaim re-executes (new sessionId), not replay the dead owner's result")
                .isNotEqualTo(firstSessionId);

        // 4. The reservation is COMPLETED again (the reclaiming owner finished).
        String status = jdbc.queryForObject(
                "select status from idempotency_record where idempotency_key = ?", String.class, key);
        assertThat(status).isEqualTo("COMPLETED");
    }

    // -------------------------------------------------------- concurrent maxSessions=1

    @Test
    void concurrentMaxSessionsOneAllowsExactlyOneSession() throws Exception {
        String token = issueToken("ai-max1-" + System.nanoTime(), null, "1");
        String caller = "ai-max1-sess-" + System.nanoTime();
        String body = createSessionBody(caller);
        int n = 6;

        // No Idempotency-Key: each request is a distinct session competing for the per-token limit.
        List<ResponseEntity<String>> responses = fireConcurrently(n, () -> postCreate(token, body));

        int created = 0, limited = 0;
        for (ResponseEntity<String> r : responses) {
            int sc = r.getStatusCode().value();
            if (sc == 201) {
                created++;
            } else if (sc == 409) {
                limited++;
                assertThat(mapper.readTree(r.getBody()).get("code").asText())
                        .as("limit-exceeded 409 carries the structured code").isEqualTo("AUTOMATION_SESSION_LIMIT_EXCEEDED");
            } else {
                throw new AssertionError("unexpected status " + sc + ": " + r.getBody());
            }
        }
        assertThat(created).as("exactly one session created under concurrency").isEqualTo(1);
        assertThat(limited).as("all other concurrent creates are limited").isEqualTo(n - 1);
        // The per-token count-then-insert (serialized by SELECT ... FOR UPDATE) held: one row.
        assertThat(sessionCountForCaller(caller))
                .as("the per-token limit held under true concurrency (one session row)")
                .isEqualTo(1);
    }

    // -------------------------------------------------------- expired-row reuse (no PK collision forever)

    @Test
    void expiredCompletedRowIsReusedRatherThanConflicting() throws Exception {
        String token = issueToken("ai-reuse-" + System.nanoTime(), null, null);
        String caller = "ai-reuse-sess-" + System.nanoTime();
        String body = createSessionBody(caller);
        String key = "idem-reuse-" + System.nanoTime();

        // 1. A request completes; its reservation is COMPLETED (and owns the primary key).
        ResponseEntity<String> first = postWithIdempotency(token, key, body);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        String firstSessionId = sessionId(first);

        // 2. Expire the row past its overall TTL so the COMPLETED result is stale.
        jdbc.update("update idempotency_record set expires_at = ? where idempotency_key = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), key);

        // 3. The same key can be REUSED (not 409 forever): the expired row is cleaned up and the
        //    request re-executes with a fresh session, rather than colliding on the primary key.
        ResponseEntity<String> reused = postWithIdempotency(token, key, body);
        assertThat(reused.getStatusCode().value())
                .as("an expired COMPLETED row must be reusable (201), not collide forever (409): %s",
                        reused.getBody())
                .isEqualTo(201);
        assertThat(sessionId(reused))
                .as("reuse re-executes (new sessionId), not replay the expired result")
                .isNotEqualTo(firstSessionId);
    }

    // -------------------------------------------------------- conflict propagates the actual X-Correlation-Id

    @Test
    void conflictPropagatesActualCorrelationIdNotTheKey() throws Exception {
        String token = issueToken("ai-corr-" + System.nanoTime(), null, null);
        String key = "idem-corr-" + System.nanoTime();
        String body1 = createSessionBody("ai-corr-1-" + System.nanoTime());
        String body2 = createSessionBody("ai-corr-2-" + System.nanoTime());

        // 1. First request with the key -> 201.
        HttpHeaders h1 = jsonHeaders(token);
        h1.set("Idempotency-Key", key);
        h1.set("X-Correlation-Id", "corr-owner");
        ResponseEntity<String> first = rest.postForEntity(base() + "/automation-sessions",
                new HttpEntity<>(body1, h1), String.class);
        assertThat(first.getStatusCode().value()).isEqualTo(201);

        // 2. Same key, different body, distinct X-Correlation-Id -> 409 conflict whose
        //    correlationId is the WAITER's header (not the idempotency key).
        HttpHeaders h2 = jsonHeaders(token);
        h2.set("Idempotency-Key", key);
        h2.set("X-Correlation-Id", "corr-waiter-xyz");
        ResponseEntity<String> conflict = rest.postForEntity(base() + "/automation-sessions",
                new HttpEntity<>(body2, h2), String.class);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        JsonNode node = mapper.readTree(conflict.getBody());
        assertThat(node.get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(node.get("correlationId").asText())
                .as("conflict must propagate the X-Correlation-Id header, not the idempotency key")
                .isEqualTo("corr-waiter-xyz");
        assertThat(node.get("correlationId").asText()).isNotEqualTo(key);
    }

    // -------------------------------------------------------- concurrency harness

    /**
     * Fire {@code n} requests concurrently, all released simultaneously by a shared latch.
     * Each response is captured (including failures) so the caller can assert on the full set.
     */
    private List<ResponseEntity<String>> fireConcurrently(int n, java.util.function.Supplier<ResponseEntity<String>> call)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return call.get();
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).as("all threads ready").isTrue();
        start.countDown(); // release every thread at once
        List<ResponseEntity<String>> results = new ArrayList<>();
        try {
            for (Future<ResponseEntity<String>> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        return results;
    }
}
