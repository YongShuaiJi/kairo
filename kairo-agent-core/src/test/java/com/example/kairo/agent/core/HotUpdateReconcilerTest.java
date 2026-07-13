package com.example.kairo.agent.core;

import com.example.kairo.api.ApplyChainStatus;
import com.example.kairo.api.bytecode.ClassIdentity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.5 &sect;4.4: hot-update reconciliation detects a bytecode-hash change between
 * applies and maps it to {@link ApplyChainStatus#TARGET_DRIFTED} (fail open); an
 * unchanged hash re-applies at a new revision.
 */
class HotUpdateReconcilerTest {

    private final HotUpdateReconciler reconciler = new HotUpdateReconciler();
    private final ClassIdentity identity = new ClassIdentity("com.example.Foo", "loader-1");

    @Test
    void firstApplyIsCompatible() {
        HotUpdateReconciler.Result result = reconciler.reconcile(identity, "hash-1");
        assertThat(result.outcome()).isEqualTo(HotUpdateReconciler.Outcome.COMPATIBLE);
        assertThat(result.previousHash()).isNull();
        assertThat(reconciler.toStatus(result)).isEqualTo(ApplyChainStatus.APPLIED);
    }

    @Test
    void unchangedHashIsCompatible() {
        reconciler.recordApplied(identity, "hash-1");
        HotUpdateReconciler.Result result = reconciler.reconcile(identity, "hash-1");
        assertThat(result.outcome()).isEqualTo(HotUpdateReconciler.Outcome.COMPATIBLE);
        assertThat(result.previousHash()).isEqualTo("hash-1");
        assertThat(reconciler.toStatus(result)).isEqualTo(ApplyChainStatus.APPLIED);
    }

    @Test
    void changedHashIsDriftedAndFailsOpen() {
        reconciler.recordApplied(identity, "hash-1");
        HotUpdateReconciler.Result result = reconciler.reconcile(identity, "hash-2");
        assertThat(result.outcome()).isEqualTo(HotUpdateReconciler.Outcome.DRIFTED);
        assertThat(result.previousHash()).isEqualTo("hash-1");
        assertThat(result.currentHash()).isEqualTo("hash-2");
        assertThat(result.reason()).contains("hash changed");
        // §4.4: drift maps to TARGET_DRIFTED so the apply chain fails open.
        assertThat(reconciler.toStatus(result)).isEqualTo(ApplyChainStatus.TARGET_DRIFTED);
    }

    @Test
    void blankCurrentHashDoesNotAssertDrift() {
        reconciler.recordApplied(identity, "hash-1");
        HotUpdateReconciler.Result result = reconciler.reconcile(identity, "");
        assertThat(result.outcome()).isEqualTo(HotUpdateReconciler.Outcome.COMPATIBLE);
    }

    @Test
    void forgetResetsToFirstApply() {
        reconciler.recordApplied(identity, "hash-1");
        reconciler.forget(identity);
        assertThat(reconciler.hasRecorded(identity)).isFalse();
        HotUpdateReconciler.Result result = reconciler.reconcile(identity, "hash-2");
        assertThat(result.outcome()).isEqualTo(HotUpdateReconciler.Outcome.COMPATIBLE);
        assertThat(result.previousHash()).isNull();
    }
}
