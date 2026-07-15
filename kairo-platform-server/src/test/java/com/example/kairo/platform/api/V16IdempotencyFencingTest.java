package com.example.kairo.platform.api;

import com.example.kairo.platform.KairoPlatformApplication;
import com.example.kairo.platform.persistence.mapper.IdempotencyRecordMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.6 acceptance safety (Codex second review): proves the owner-token fencing and the live-owner
 * lease heartbeat that close the remaining {@link IdempotencyFilter} correctness gaps.
 *
 * <ul>
 *   <li><b>Fencing</b> &mdash; after a lease is reclaimed, the stale owner's complete/delete hit
 *       zero rows (owner_token mismatch); only the new owner can complete.</li>
 *   <li><b>Heartbeat</b> &mdash; a slow live owner renews its lease, so a concurrent same-key waiter
 *       WAITS and REPLAYS rather than reclaiming and double-executing. A request that outlives the
 *       wait window returns {@code 409 IDEMPOTENCY_KEY_IN_PROGRESS} (the live owner is never
 *       reclaimed) instead of double-executing.</li>
 * </ul>
 *
 * <p>Lives in the {@code com.example.kairo.platform.api} package so it can construct the
 * package-private {@link IdempotencyFilter} with fast, deterministic timing.
 */
@SpringBootTest(classes = KairoPlatformApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v16_fencing;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@ActiveProfiles("test")
class V16IdempotencyFencingTest {

    @Autowired IdempotencyRecordMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from idempotency_record");
    }

    // -------------------------------------------------------- owner-token fencing (issue 2)

    @Test
    void staleOwnerCannotCompleteOrDeleteReclaimedReservation() {
        String key = "fence-" + System.nanoTime();
        String actor = "ai-fence";
        String hash = "hash-" + System.nanoTime();
        Instant now = Instant.now();

        // Owner A reserves.
        String tokenA = "owner-token-A";
        mapper.insertReservation(key, actor, hash, "IN_PROGRESS", tokenA,
                Timestamp.from(now.plusSeconds(30)), Timestamp.from(now),
                Timestamp.from(now.plusSeconds(86_400)));

        // A stalls and its lease elapses.
        jdbc.update("update idempotency_record set lease_expires_at = ? where idempotency_key = ?",
                Timestamp.from(now.minusSeconds(60)), key);

        // Waiter B reclaims the expired lease; ownership transfers to tokenB.
        String tokenB = "owner-token-B";
        int reclaimed = mapper.reclaimReservation(key, actor, hash, tokenB,
                Timestamp.from(Instant.now().plusSeconds(30)), Timestamp.from(Instant.now()));
        assertThat(reclaimed).as("B reclaims the expired lease").isEqualTo(1);

        // Stale owner A tries to complete its (now-reclaimed) reservation -> fenced out (0 rows).
        int staleComplete = mapper.completeRecord(key, tokenA, 200, "{\"stale\":true}",
                Timestamp.from(Instant.now()));
        assertThat(staleComplete).as("stale owner A cannot complete the reclaimed row").isZero();

        // Stale owner A tries to release/delete -> fenced out (0 rows).
        int staleDelete = mapper.deleteReservation(key, tokenA);
        assertThat(staleDelete).as("stale owner A cannot delete the reclaimed row").isZero();

        // The row is still IN_PROGRESS owned by B (A's complete/delete were fenced out).
        Map<String, Object> row = jdbc.queryForMap(
                "select status, owner_token from idempotency_record where idempotency_key = ?", key);
        assertThat(row.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(row.get("owner_token")).isEqualTo(tokenB);

        // New owner B completes successfully.
        int bComplete = mapper.completeRecord(key, tokenB, 201, "{\"real\":true}",
                Timestamp.from(Instant.now()));
        assertThat(bComplete).as("new owner B completes").isEqualTo(1);
        Map<String, Object> done = jdbc.queryForMap(
                "select status, response_json, owner_token from idempotency_record where idempotency_key = ?", key);
        assertThat(done.get("status")).isEqualTo("COMPLETED");
        assertThat(String.valueOf(done.get("response_json"))).isEqualTo("{\"real\":true}");
        assertThat(done.get("owner_token")).as("completing clears the owner token").isNull();

        jdbc.update("delete from idempotency_record where idempotency_key = ?", key);
    }

    // -------------------------------------------------------- slow live owner is not reclaimed (issue 3)

    @Test
    void slowLiveOwnerIsNotReclaimedAndWaiterReplays() throws Exception {
        // lease=1s (renewed every 150ms while the owner runs), maxWait=10s: a 1.5s owner keeps its
        // lease alive, so a concurrent waiter WAITS and REPLAYS rather than reclaiming.
        IdempotencyFilter filter = new IdempotencyFilter(mapper, 1000L, 150L, 10_000L, 50L);
        try {
            String key = "idem-slow-" + System.nanoTime();
            String body = "{\"x\":1}";

            CountDownLatch ownerStarted = new CountDownLatch(1);
            CountDownLatch ownerProceed = new CountDownLatch(1);
            AtomicInteger ownerInvocations = new AtomicInteger();
            AtomicInteger waiterInvocations = new AtomicInteger();

            FilterChain ownerChain = newChain(() -> {
                ownerInvocations.incrementAndGet();
                ownerStarted.countDown();
                await(ownerProceed, 5);
            }, 200, "{\"ok\":\"owner\"}");

            CompletableFuture<Void> owner = CompletableFuture.runAsync(() -> {
                MockHttpServletRequest req = request(key, body, "corr-owner");
                MockHttpServletResponse res = new MockHttpServletResponse();
                try {
                    filter.doFilter(req, res, ownerChain);
                    assertThat(res.getStatus()).isEqualTo(200);
                    assertThat(res.getContentAsString()).isEqualTo("{\"ok\":\"owner\"}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertThat(ownerStarted.await(2, TimeUnit.SECONDS))
                    .as("owner must be executing").isTrue();

            // Waiter: same key+body. It should WAIT (lease kept alive by heartbeat) and REPLAY.
            FilterChain waiterChain = newChain(() -> waiterInvocations.incrementAndGet(), 0, "");
            CompletableFuture<Void> waiter = CompletableFuture.runAsync(() -> {
                MockHttpServletRequest req = request(key, body, "corr-waiter");
                MockHttpServletResponse res = new MockHttpServletResponse();
                try {
                    filter.doFilter(req, res, waiterChain);
                    assertThat(res.getStatus())
                            .as("waiter replays the owner's 200, not a conflict").isEqualTo(200);
                    assertThat(res.getContentAsString()).isEqualTo("{\"ok\":\"owner\"}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // While the owner is live, the waiter must not have executed its chain.
            Thread.sleep(300);
            assertThat(waiterInvocations.get())
                    .as("waiter must not execute while the live owner holds the reservation").isZero();

            ownerProceed.countDown(); // release the owner; it completes 200
            owner.get(10, TimeUnit.SECONDS);
            waiter.get(10, TimeUnit.SECONDS);

            assertThat(ownerInvocations.get()).as("owner executed exactly once").isEqualTo(1);
            assertThat(waiterInvocations.get())
                    .as("waiter replayed the cached result, never executed").isZero();
        } finally {
            filter.destroy();
        }
    }

    // -------------------------------------------------------- live owner beyond wait window is not reclaimed (issue 3)

    @Test
    void liveOwnerBeyondMaxWaitReturnsStillProcessingNotReclaim() throws Exception {
        // lease=800ms (renewed every 100ms), maxWait=1.5s, owner runs 3s (> maxWait). The waiter's
        // wait window elapses while the owner's lease is still live, so it must NOT reclaim; it
        // returns 409 IDEMPOTENCY_KEY_IN_PROGRESS and the owner still completes exactly once.
        IdempotencyFilter filter = new IdempotencyFilter(mapper, 800L, 100L, 1_500L, 50L);
        try {
            String key = "idem-slowproc-" + System.nanoTime();
            String body = "{\"x\":2}";

            CountDownLatch ownerStarted = new CountDownLatch(1);
            CountDownLatch ownerProceed = new CountDownLatch(1);
            AtomicInteger ownerInvocations = new AtomicInteger();
            AtomicInteger waiterInvocations = new AtomicInteger();

            FilterChain ownerChain = newChain(() -> {
                ownerInvocations.incrementAndGet();
                ownerStarted.countDown();
                await(ownerProceed, 8);
            }, 200, "{\"ok\":\"owner\"}");

            CompletableFuture<Void> owner = CompletableFuture.runAsync(() -> {
                MockHttpServletRequest req = request(key, body, "corr-owner");
                MockHttpServletResponse res = new MockHttpServletResponse();
                try {
                    filter.doFilter(req, res, ownerChain);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertThat(ownerStarted.await(2, TimeUnit.SECONDS)).isTrue();

            FilterChain waiterChain = newChain(() -> waiterInvocations.incrementAndGet(), 0, "");
            CompletableFuture<MockHttpServletResponse> waiter = CompletableFuture.supplyAsync(() -> {
                MockHttpServletRequest req = request(key, body, "corr-waiter");
                MockHttpServletResponse res = new MockHttpServletResponse();
                try {
                    filter.doFilter(req, res, waiterChain);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return res;
            });

            // The waiter returns 409 IDEMPOTENCY_KEY_IN_PROGRESS (live owner not reclaimed), quickly.
            MockHttpServletResponse waiterResp = waiter.get(10, TimeUnit.SECONDS);
            assertThat(waiterResp.getStatus())
                    .as("a live owner beyond the wait window returns 409, not a reclaim/double-exec")
                    .isEqualTo(409);
            String body409 = waiterResp.getContentAsString();
            assertThat(body409).contains("IDEMPOTENCY_KEY_IN_PROGRESS");
            assertThat(body409).contains("\"corr-waiter\""); // X-Correlation-Id propagated
            assertThat(waiterInvocations.get())
                    .as("waiter did not execute the chain (no double execution)").isZero();

            ownerProceed.countDown();
            owner.get(10, TimeUnit.SECONDS);
            assertThat(ownerInvocations.get()).as("owner executed exactly once").isEqualTo(1);
            assertThat(waiterInvocations.get())
                    .as("waiter never executed even after the owner completed").isZero();
        } finally {
            filter.destroy();
        }
    }

    // -------------------------------------------------------- helpers

    private static void await(CountDownLatch latch, long seconds) {
        try {
            if (!latch.await(seconds, TimeUnit.SECONDS)) {
                throw new AssertionError("latch not released within " + seconds + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static MockHttpServletRequest request(String key, String body, String correlationId) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/test-mutation");
        req.addHeader("Idempotency-Key", key);
        req.addHeader("X-Correlation-Id", correlationId);
        req.setContentType("application/json");
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        return req;
    }

    /** A minimal filter chain that runs {@code body}, then writes the given status/json response. */
    private static FilterChain newChain(Runnable body, int status, String json) {        return new FilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response)
                    throws IOException, jakarta.servlet.ServletException {
                body.run();
                if (status != 0 && response instanceof HttpServletResponse http) {
                    http.setStatus(status);
                    http.setContentType("application/json");
                    http.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    http.getWriter().write(json);
                }
            }
        };
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from idempotency_record");
    }
}
