package com.example.kairo.platform.command;

import com.example.kairo.platform.metrics.KairoMetricsRecorder;
import com.example.kairo.platform.persistence.mapper.AgentCommandMapper;
import com.example.kairo.platform.service.PlatformCoreService;
import com.example.kairo.platform.service.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V1.7 M1-B &sect;8.2: Platform restart recovery for Agent commands.
 *
 * <p>Runs once on every application context start (as an {@link ApplicationRunner}). It scans
 * commands created before this process's startup boundary that remain non-terminal
 * ({@code PENDING} / {@code DISPATCHED}), applies the fixed
 * {@link AgentCommandClassification} in Java, and atomically fails every orphan
 * <strong>TRANSIENT</strong> command with the fixed code
 * {@link AgentCommandClassification#TRANSIENT_CONTEXT_LOST_CODE}. DURABLE commands are left
 * untouched so that, against the same persistent database, a PENDING DURABLE command stays
 * claimable and an expired DISPATCHED DURABLE command stays redispatchable under M1-A;
 * ACKED/FAILED terminal commands never match the guard.
 *
 * <p><b>Idempotent</b>: each row is failed inside its own transaction guarded on
 * {@code status IN (PENDING, DISPATCHED)}, so a second startup (or a direct re-run of
 * {@link #recoverOrphanTransientCommands()}) matches zero rows for already-recovered
 * commands and records no duplicate audit evidence.
 *
 * <p><b>Secure by construction</b>: the recovery reads only {@code id, command_type, status,
 * attempts} (never {@code payload_json} or {@code result_json}) and the audit event it
 * records carries only the command type, previous status, attempts and the fixed code.
 * Script source, class bytes, Authorization headers and tokens are therefore never loaded
 * or persisted by the recovery; the in-memory transient exchanges stay non-durable.
 */
@Service
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CommandStartupRecoveryService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CommandStartupRecoveryService.class);

    private static final String RECOVERY_ACTION = "agent_command.recover_transient";
    private static final String RECOVERY_REASON =
            "transient command context lost on platform restart";

    private final AgentCommandMapper commandMapper;
    private final PlatformCoreService eventWriter;
    private final Clock clock;
    private final Instant startupBoundary;
    private final TransactionTemplate transactionTemplate;
    private KairoMetricsRecorder metricsRecorder = KairoMetricsRecorder.NO_OP;

    /** V1.7 M4-B &sect;11.2: metrics recorder for recovered-command outcomes; no-op when not injected. */
    @Autowired
    void setMetricsRecorder(KairoMetricsRecorder metricsRecorder) {
        this.metricsRecorder = metricsRecorder;
    }

    @Autowired
    public CommandStartupRecoveryService(AgentCommandMapper commandMapper,
                                          PlatformCoreService eventWriter,
                                          TransactionTemplate transactionTemplate) {
        this(commandMapper, eventWriter, Clock.systemUTC(), transactionTemplate);
    }

    CommandStartupRecoveryService(AgentCommandMapper commandMapper,
                                  PlatformCoreService eventWriter,
                                  Clock clock,
                                  TransactionTemplate transactionTemplate) {
        this.commandMapper = commandMapper;
        this.eventWriter = eventWriter;
        this.clock = clock;
        // Capture this before the web server can accept work. ApplicationRunner executes after
        // the context (and embedded server) has started, so an unbounded scan could otherwise
        // fail a TRANSIENT command created by the new process while recovery is running.
        this.startupBoundary = clock.instant();
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        RecoveryResult result = recoverOrphanTransientCommands();
        if (result.recovered() > 0) {
            log.info("V1.7 M1-B startup recovery: failed {} orphan TRANSIENT command(s) with {}; "
                            + "scanned {} non-terminal command(s). DURABLE commands left "
                            + "claimable/redispatchable, terminal commands untouched.",
                    result.recovered(), AgentCommandClassification.TRANSIENT_CONTEXT_LOST_CODE,
                    result.scanned());
        }
    }

    /**
     * Fail every pre-startup orphan TRANSIENT command (PENDING/DISPATCHED) with the fixed code,
     * recording one minimal audit event per recovered row. Commands created by this process are
     * outside the captured startup boundary and remain untouched. Returns a summary of the pass;
     * safe to call repeatedly (idempotent: already-terminal rows match zero rows and produce no
     * audit).
     */
    public RecoveryResult recoverOrphanTransientCommands() {
        List<Map<String, Object>> candidates = commandMapper.findPendingOrDispatchedCommands(
                Timestamp.from(startupBoundary));
        int recovered = 0;
        for (Map<String, Object> raw : candidates) {
            Map<String, Object> row = lowerKeys(raw);
            String commandType = String.valueOf(row.get("command_type"));
            if (!AgentCommandClassification.isTransient(commandType)) {
                continue;
            }
            String id = String.valueOf(row.get("id"));
            String previousStatus = String.valueOf(row.get("status"));
            long attempts = asLong(row.get("attempts"));
            Integer updated = transactionTemplate.execute(status ->
                    failAndAudit(id, commandType, previousStatus, attempts));
            if (updated != null && updated > 0) {
                recovered++;
                // V1.7 M4-B §11.2: a recovered transient command reached a terminal FAILED outcome
                // (context lost on restart). Recorded after the per-row transaction committed.
                try {
                    metricsRecorder.recordCommandOutcome(commandType, "FAILURE");
                } catch (RuntimeException e) {
                    // An observability backend must not turn a completed recovery into startup failure.
                    log.warn("Recovered-command metric recording failed for {}: {}", commandType, e.getMessage());
                }
            }
        }
        return new RecoveryResult(recovered, candidates.size());
    }

    /** Fail one orphan row and record its minimal recovery audit, inside the caller's transaction. */
    private Integer failAndAudit(String id, String commandType, String previousStatus, long attempts) {
        Instant now = clock.instant();
        Timestamp nowTs = Timestamp.from(now);
        int updated = commandMapper.failTransientCommand(id,
                AgentCommandClassification.TRANSIENT_CONTEXT_LOST_CODE, nowTs);
        if (updated == 0) {
            // Already terminal (a concurrent ack/exhaustion or a prior recovery) - no-op, no audit.
            return 0;
        }
        // Minimal diagnostics only: commandType, previous status, attempts and the fixed code.
        // Never the payload, so script source / class bytes / tokens cannot leak into audit.
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("commandType", commandType);
        before.put("status", previousStatus);
        before.put("attempts", attempts);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("commandType", commandType);
        after.put("status", "FAILED");
        after.put("errorCode", AgentCommandClassification.TRANSIENT_CONTEXT_LOST_CODE);
        after.put("attempts", attempts);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("commandType", commandType);
        details.put("previousStatus", previousStatus);
        details.put("errorCode", AgentCommandClassification.TRANSIENT_CONTEXT_LOST_CODE);
        details.put("reason", RECOVERY_REASON);
        RequestContext recoveryContext = new RequestContext("system",
                "recovery-" + UUID.randomUUID(), "127.0.0.1", "system", "startup");
        eventWriter.recordEvent(recoveryContext, RECOVERY_ACTION, "agent_command", id,
                attempts, before, after, "FAILED", RECOVERY_REASON, details);
        return updated;
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0L : Long.parseLong(String.valueOf(value));
    }

    private static Map<String, Object> lowerKeys(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (row != null) {
            row.forEach((key, value) -> normalized.put(key == null ? null : key.toLowerCase(), value));
        }
        return normalized;
    }

    /** Summary of one recovery pass: rows actually recovered vs. non-terminal rows scanned. */
    public record RecoveryResult(int recovered, int scanned) {
    }
}
