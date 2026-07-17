package com.example.kairo.platform.command;

import com.example.kairo.platform.persistence.mapper.AgentCommandMapper;
import com.example.kairo.platform.persistence.mapper.TestPlatformMapper;
import com.example.kairo.platform.service.BusinessIdService;
import com.example.kairo.platform.service.PlatformCoreService;
import com.example.kairo.platform.service.RbacService;
import com.example.kairo.platform.service.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M1-A &sect;8.1: the Agent command claim/lease state machine. Exercises the atomic
 * {@code dispatchCommand} claim (PENDING always claimable; DISPATCHED claimable only once its
 * lease expired), the per-dispatch {@code attempts} epoch, live-lease non-preemption, single-taker
 * expired-lease reclaim, and terminal exhaustion to {@code FAILED(MAX_ATTEMPTS_EXHAUSTED)}.
 *
 * <p>The service is constructed directly with a {@link MutableClock} (the injectable-Clock seam the
 * plan requires) so leases expire by advancing the clock, never by sleeping. Concurrency proofs use
 * a {@link CyclicBarrier} so two pollers race the same command; the atomic conditional UPDATE is the
 * single winner-decider, so the assertion is timing-independent. A dedicated in-memory H2
 * ({@code DB_CLOSE_DELAY=-1}) isolates these tests and keeps the named DB alive across the pooled
 * connections the racing threads use.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v17_m1a;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class AgentCommandLeaseIntegrationTest {

    @Autowired AgentCommandMapper commandMapper;
    @Autowired RbacService rbacService;
    @Autowired PlatformCoreService eventWriter;
    @Autowired BusinessIdService businessIdService;
    @Autowired CapabilityGate capabilityGate;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestPlatformMapper fixtures;

    private MutableClock clock;
    private AgentCommandService commands;
    private String agentId;
    private String instanceId;
    private RequestContext admin;
    private RequestContext agentCtx;

    @BeforeEach
    void setUp() {
        fixtures.ensureDefaultProject();
        fixtures.ensureDefaultApplication();
        fixtures.ensureDefaultEnvironment();
        agentId = "agent-lease-" + UUID.randomUUID();
        instanceId = "inst-lease-" + UUID.randomUUID();
        jdbc.update("""
                insert into instance(id, application_id, environment_id, nickname, hostname, process_id, runtime,
                  status, labels_json, created_at, updated_at)
                values (?, 'app-default', 'env-dev', ?, 'localhost', '1', 'java', 'ACTIVE', '{}',
                  current_timestamp, current_timestamp)
                """, instanceId, instanceId);
        jdbc.update("""
                insert into agent_instance(id, instance_id, status, agent_version, bootstrap_version,
                  listen_host, listen_port, token_hash, capabilities_json, created_at, updated_at)
                values (?, ?, 'ACTIVE', 'test', 'test', '127.0.0.1', 1, 'hash-only', '[]',
                  current_timestamp, current_timestamp)
                """, agentId, instanceId);
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        commands = new AgentCommandService(commandMapper, rbacService, eventWriter,
                businessIdService, clock, capabilityGate);
        admin = new RequestContext("system", "corr", "127.0.0.1", "header-dev", "test");
        agentCtx = new RequestContext(agentId, "corr", "127.0.0.1", "agent", "test");
    }

    @AfterEach
    void tearDown() {
        if (agentId != null) {
            jdbc.update("delete from agent_command where agent_id = ?", agentId);
            jdbc.update("delete from agent_instance where id = ?", agentId);
        }
        if (instanceId != null) {
            jdbc.update("delete from instance where id = ?", instanceId);
        }
    }

    @Test
    void pendingCommandIsClaimedAndBumpsAttempts() {
        String id = createCommand("DISABLE_ALL", 5);
        Map<String, Object> polled = poll();
        assertThat(polled.get("status")).isEqualTo("DISPATCHED");
        assertThat(polled.get("id")).isEqualTo(id);
        // §8.1 #2: each successful dispatch bumps attempts and the response carries the new epoch.
        assertThat(((Number) polled.get("attempts")).longValue()).isEqualTo(1L);
        assertThat(commandAttempts(id)).isEqualTo(1L);
    }

    @Test
    void liveLeaseCannotBeStolen() {
        String id = createCommand("DISABLE_ALL", 5);
        poll(); // attempts=1, lease = now + 60s
        clock.advance(Duration.ofSeconds(30)); // still within the live lease
        Map<String, Object> second = poll();
        assertThat(second.get("status")).isEqualTo("NO_COMMAND"); // §4.3: live lease is not preemptable
        assertThat(commandStatus(id)).isEqualTo("DISPATCHED");
        assertThat(commandAttempts(id)).isEqualTo(1L);
    }

    @Test
    void expiredLeaseIsReclaimedWithNewEpoch() {
        String id = createCommand("DISABLE_ALL", 5);
        poll(); // attempts=1
        clock.advance(Duration.ofSeconds(61)); // lease expired, attempts < max -> reclaimable
        Map<String, Object> reclaimed = poll();
        assertThat(reclaimed.get("status")).isEqualTo("DISPATCHED");
        // §4.3: reclaim bumps attempts to a new epoch the new owner echoes on ack.
        assertThat(((Number) reclaimed.get("attempts")).longValue()).isEqualTo(2L);
        assertThat(commandAttempts(id)).isEqualTo(2L);
    }

    @Test
    void twoConcurrentClaimsOnlyOneSucceeds() throws Exception {
        String id = createCommand("DISABLE_ALL", 5);
        List<Map<String, Object>> results = raceTwoPolls();
        long dispatched = results.stream()
                .filter(r -> !"NO_COMMAND".equals(r.get("status")))
                .count();
        // §8.1 / §4.3: exactly one claim wins per dispatch round (atomic conditional UPDATE).
        assertThat(dispatched).isEqualTo(1L);
        assertThat(commandAttempts(id)).isEqualTo(1L);
    }

    @Test
    void expiredLeaseHasOnlyOneTaker() throws Exception {
        String id = createCommand("DISABLE_ALL", 5);
        poll(); // attempts=1
        clock.advance(Duration.ofSeconds(61)); // expired
        List<Map<String, Object>> results = raceTwoPolls();
        long reclaimed = results.stream()
                .filter(r -> !"NO_COMMAND".equals(r.get("status")))
                .count();
        // §8.1: expired lease has exactly one taker; the loser's UPDATE matches zero rows because
        // the winner moved the lease into the future and bumped attempts.
        assertThat(reclaimed).isEqualTo(1L);
        assertThat(commandAttempts(id)).isEqualTo(2L);
    }

    @Test
    void exhaustedCommandTerminatesFailedWithFixedCode() {
        String id = createCommand("DISABLE_ALL", 1); // maxAttempts=1
        poll(); // attempts=1 == maxAttempts
        clock.advance(Duration.ofSeconds(61)); // lease expired and attempts >= maxAttempts
        Map<String, Object> result = poll(); // §8.1 #7: atomic exhaustion sweep terminates it
        assertThat(result.get("status")).isEqualTo("NO_COMMAND");
        assertThat(commandStatus(id)).isEqualTo("FAILED");
        // §8.1 #7: the fixed error code is AGENT_COMMAND_MAX_ATTEMPTS_EXHAUSTED.
        assertThat(commandErrorMessage(id)).isEqualTo("AGENT_COMMAND_MAX_ATTEMPTS_EXHAUSTED");
        assertThat(commandAttempts(id)).isEqualTo(1L); // not re-dispatched past exhaustion
    }

    @Test
    void exhaustedSweepLeavesReclaimableCommandForRedispatch() {
        String id = createCommand("DISABLE_ALL", 2); // maxAttempts=2
        poll(); // attempts=1
        clock.advance(Duration.ofSeconds(61)); // expired but attempts(1) < max(2) -> not exhausted
        Map<String, Object> reclaimed = poll();
        assertThat(reclaimed.get("status")).isEqualTo("DISPATCHED");
        assertThat(((Number) reclaimed.get("attempts")).longValue()).isEqualTo(2L);
        // now exhausted: a further expiry terminates rather than re-dispatching again.
        clock.advance(Duration.ofSeconds(61));
        Map<String, Object> terminal = poll();
        assertThat(terminal.get("status")).isEqualTo("NO_COMMAND");
        assertThat(commandStatus(id)).isEqualTo("FAILED");
        assertThat(commandErrorMessage(id)).isEqualTo("AGENT_COMMAND_MAX_ATTEMPTS_EXHAUSTED");
    }

    // -------------------------------------------------------- helpers

    private String createCommand(String type, long maxAttempts) {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("commandType", type);
        request.put("maxAttempts", maxAttempts);
        Map<String, Object> created = commands.createManualCommand(admin, agentId, request);
        return String.valueOf(created.get("id"));
    }

    private Map<String, Object> poll() {
        return commands.pollNext(agentId, agentCtx, Map.of("leaseSeconds", 60));
    }

    /** Run two pollNext calls concurrently, released together by a barrier (no long sleeps). */
    private List<Map<String, Object>> raceTwoPolls() throws Exception {
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        Callable<Map<String, Object>> task = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return poll();
        };
        List<Callable<Map<String, Object>>> tasks = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            tasks.add(task);
        }
        List<Future<Map<String, Object>>> futures;
        try {
            futures = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        List<Map<String, Object>> results = new ArrayList<>(threads);
        for (Future<Map<String, Object>> future : futures) {
            results.add(future.get(5, TimeUnit.SECONDS));
        }
        return results;
    }

    private String commandStatus(String id) {
        return jdbc.queryForObject("select status from agent_command where id = ?", String.class, id);
    }

    private long commandAttempts(String id) {
        return jdbc.queryForObject("select attempts from agent_command where id = ?",
                Number.class, id).longValue();
    }

    private String commandErrorMessage(String id) {
        return jdbc.queryForObject("select error_message from agent_command where id = ?",
                String.class, id);
    }
}
