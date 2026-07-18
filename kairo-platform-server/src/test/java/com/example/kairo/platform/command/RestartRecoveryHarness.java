package com.example.kairo.platform.command;

import com.example.kairo.platform.KairoPlatformApplication;
import com.example.kairo.platform.persistence.mapper.AgentCommandMapper;
import com.example.kairo.platform.persistence.mapper.TestPlatformMapper;
import com.example.kairo.platform.service.RequestContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.UUID;

/**
 * V1.7 M1-B &sect;8.2 restart test harness. The plan forbids "reusing one context, mocking a
 * restart, or only calling a recovery method twice": a real Platform restart must close one
 * Spring application context and create a new one against the same persistent database. This
 * harness does exactly that - it launches {@link KairoPlatformApplication} as a real
 * {@link SpringApplication} (full autoconfiguration, the real {@code CommandStartupRecoveryService}
 * {@code ApplicationRunner}, Flyway against a file-backed H2) and exposes its beans so a test
 * can set up state in context #1, close it, start context #2 against the same H2 file, and
 * assert the recovery/redispatch/no-replay behaviour.
 *
 * <p>Time is deterministic: there are no sleeps. Lease expiry is driven by overwriting
 * {@code lease_expires_at} to a fixed past timestamp via {@link #expireLease(JdbcTemplate, String)}.
 * Scheduled maintenance/rollout tasks are pushed ~11 days into the future (and the rollout
 * scheduler disabled) so they cannot mutate command rows mid-test. Each test method owns a
 * fresh {@code @TempDir} H2 file, so methods are fully isolated and need no cross-method data
 * cleanup.
 *
 * <p>The web server is started on a random port ({@code server.port=0}) so two sequential
 * contexts never collide on the platform port; tests interact through service beans, not HTTP.
 */
final class RestartRecoveryHarness {

    /** A fixed far-past timestamp so any {@code now} satisfies {@code lease_expires_at <= now}. */
    static final String EXPIRED_LEASE_TS = "2020-01-01 00:00:00";

    private final Path dbDir;
    private ConfigurableApplicationContext context;

    RestartRecoveryHarness(Path dbDir) {
        this.dbDir = dbDir;
    }

    /** The file-backed H2 URL shared by every context this harness starts. */
    String jdbcUrl() {
        return "jdbc:h2:file:" + dbDir.resolve("kairo-restart")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
    }

    /** Launch a fresh Platform application context against the shared H2 file. */
    ConfigurableApplicationContext start() {
        SpringApplication app = new SpringApplication(KairoPlatformApplication.class);
        app.setAdditionalProfiles("test");
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setRegisterShutdownHook(false);
        app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        context = app.run(
                "--spring.datasource.url=" + jdbcUrl(),
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.flyway.enabled=true",
                "--server.port=0",
                // Neutralize every @Scheduled maintenance/rollout task so it cannot mutate
                // command rows between context start and the test assertions (~11 day delay).
                "--kairo.platform.runtime-lease.initial-delay-ms=999999999",
                "--kairo.platform.runtime-lease.fixed-delay-ms=999999999",
                "--kairo.platform.runtime-cleanup.initial-delay-ms=999999999",
                "--kairo.platform.runtime-cleanup.fixed-delay-ms=999999999",
                "--kairo.platform.rollout.scheduler.enabled=false",
                "--logging.level.root=WARN",
                "--logging.level.com.example.kairo.platform.command=INFO"
        );
        return context;
    }

    /** Close the current context (releasing the datasource pool); safe to call when none open. */
    void stop() {
        if (context != null) {
            try {
                context.close();
            } finally {
                context = null;
            }
        }
    }

    AgentCommandService commands() {
        return context.getBean(AgentCommandService.class);
    }

    AgentCommandMapper commandMapper() {
        return context.getBean(AgentCommandMapper.class);
    }

    JdbcTemplate jdbc() {
        return context.getBean(JdbcTemplate.class);
    }

    TestPlatformMapper fixtures() {
        return context.getBean(TestPlatformMapper.class);
    }

    CommandStartupRecoveryService recovery() {
        return context.getBean(CommandStartupRecoveryService.class);
    }

    /** Seed the reference project/application/environment plus an instance + agent. */
    void seedRuntime(String agentId, String instanceId) {
        TestPlatformMapper fixtures = fixtures();
        fixtures.ensureDefaultProject();
        fixtures.ensureDefaultApplication();
        fixtures.ensureDefaultEnvironment();
        JdbcTemplate jdbc = jdbc();
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
    }

    /** A manager RequestContext (the V1-seeded {@code system} super-admin). */
    static RequestContext admin() {
        return new RequestContext("system", "corr-" + UUID.randomUUID(),
                "127.0.0.1", "header-dev", "test");
    }

    /** The agent's own RequestContext (matches the polling agent's actor). */
    static RequestContext agentContext(String agentId) {
        return new RequestContext(agentId, "corr-" + UUID.randomUUID(),
                "127.0.0.1", "agent", "test");
    }

    /** Deterministically expire a dispatched command's lease (no sleeps): set lease_expires_at to a fixed past. */
    static void expireLease(JdbcTemplate jdbc, String commandId) {
        jdbc.update("update agent_command set lease_expires_at = timestamp '" + EXPIRED_LEASE_TS
                + "' where id = ?", commandId);
    }
}
