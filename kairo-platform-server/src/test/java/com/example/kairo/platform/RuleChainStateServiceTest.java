package com.example.kairo.platform;

import com.example.kairo.api.ChainDesiredState;
import com.example.kairo.api.RuleChainRevision;
import com.example.kairo.api.ReconcileResult;
import com.example.kairo.api.ReconcileStatus;
import com.example.kairo.platform.service.RuleChainStateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.4 platform desired/actual state: the CAS on the monotonic revision fences out-of-order
 * and concurrent publishes (&sect;3.3 / &sect;7). A publish that believes the current revision
 * is {@code N} only lands when the stored revision is still {@code N}; a stale or late command
 * loses the CAS and is rejected.
 */
@SpringBootTest
@ActiveProfiles("test")
class RuleChainStateServiceTest {

    @Autowired RuleChainStateService stateService;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        jdbc.update("delete from rule_chain_instance_state");
        jdbc.update("delete from rule_chain_operation_target");
        jdbc.update("delete from rule_chain_operation");
        jdbc.update("delete from rule_chain_desired_state where created_by = 'v14-test'");
    }

    @Test
    void casAdvancesRevisionWhenExpectedMatches() {
        boolean first = stateService.casDesiredChain("app", "env", "agent-1", "chain-1",
                "com.example.Svc", "echo", "(I)I", "METHOD_RETURN", null,
                0L, 1L, "hash-1", ChainDesiredState.ACTIVE, 0L, "[]", "v14-test");
        assertThat(first).isTrue();

        boolean second = stateService.casDesiredChain("app", "env", "agent-1", "chain-1",
                "com.example.Svc", "echo", "(I)I", "METHOD_RETURN", null,
                1L, 2L, "hash-2", ChainDesiredState.ACTIVE, 0L, "[]", "v14-test");
        assertThat(second).isTrue();
    }

    @Test
    void staleExpectedRevisionIsFenced() {
        stateService.casDesiredChain("app", "env", "agent-1", "chain-stale",
                "com.example.Svc", "echo", "(I)I", "METHOD_RETURN", null,
                0L, 1L, "hash-1", ChainDesiredState.ACTIVE, 0L, "[]", "v14-test");

        // A late command still believes revision is 0; it must be rejected.
        boolean late = stateService.casDesiredChain("app", "env", "agent-1", "chain-stale",
                "com.example.Svc", "echo", "(I)I", "METHOD_RETURN", null,
                0L, 2L, "hash-late", ChainDesiredState.ACTIVE, 0L, "[]", "v14-test");
        assertThat(late).isFalse();
    }

    @Test
    void duplicateIdempotentRevisionIsRejected() {
        stateService.casDesiredChain("app", "env", "agent-1", "chain-dup",
                "com.example.Svc", "echo", "(I)I", "METHOD_RETURN", null,
                0L, 1L, "hash-1", ChainDesiredState.ACTIVE, 0L, "[]", "v14-test");

        // Re-applying the same (expected=0, desired=1) loses: revision is already 1.
        boolean replay = stateService.casDesiredChain("app", "env", "agent-1", "chain-dup",
                "com.example.Svc", "echo", "(I)I", "METHOD_RETURN", null,
                0L, 1L, "hash-1", ChainDesiredState.ACTIVE, 0L, "[]", "v14-test");
        assertThat(replay).isFalse();
    }

    @Test
    void reconcileReportsBehindBeforeAck() {
        stateService.casDesiredChain("app", "env", "agent-1", "chain-reconcile",
                "com.example.Svc", "echo", "(I)I", "METHOD_RETURN", null,
                0L, 2L, "hash-2", ChainDesiredState.ACTIVE, 0L, "[]", "v14-test");

        ReconcileResult result = stateService.reconcile("app", "env", "agent-1", "chain-reconcile",
                new RuleChainRevision(2L, "hash-2"));
        // No instance ack yet -> the agent is behind / unknown.
        assertThat(result.status()).isIn(ReconcileStatus.BEHIND, ReconcileStatus.UNKNOWN);
    }

    @Test
    void reconcileReportsInSyncAfterAck() {
        stateService.casDesiredChain("app", "env", "agent-1", "chain-sync",
                "com.example.Svc", "echo", "(I)I", "METHOD_RETURN", null,
                0L, 1L, "hash-1", ChainDesiredState.ACTIVE, 0L, "[]", "v14-test");

        String desiredId = jdbc.queryForObject(
                "select id from rule_chain_desired_state where chain_id = 'chain-sync'",
                String.class);
        stateService.recordInstanceAck(desiredId, "agent-1", new RuleChainRevision(1L, "hash-1"),
                1L, "th-1", null, "APPLIED");

        ReconcileResult result = stateService.reconcile("app", "env", "agent-1", "chain-sync",
                new RuleChainRevision(1L, "hash-1"));
        assertThat(result.status()).isEqualTo(ReconcileStatus.IN_SYNC);
    }
}
