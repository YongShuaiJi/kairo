package com.example.kairo.agent.core.bytecode;

import com.example.kairo.agent.core.ByteBuddyTransformerManager;
import com.example.kairo.api.bytecode.BytecodeSnapshotKind;
import com.example.kairo.api.bytecode.BytecodeSnapshotMetadata;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.TransformationDiagnostic;
import com.example.kairo.api.bytecode.TransformationResult;
import com.example.kairo.api.bytecode.TransformationRevision;
import com.example.kairo.api.bytecode.TransformationStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Re-reads the bytes actually running in the JVM for a class through the manager's
 * short-lived capturing transformer and stores an {@link BytecodeSnapshotKind#APPLIED}
 * snapshot. Capture never advances a revision; it anchors the captured bytes at the
 * class's current revision and verifies them against the last recorded transform output.
 *
 * <p>Must be invoked from a control or diagnostic thread, never a business thread: the
 * capture triggers a retransform of the target class.
 */
public final class BytecodeCaptureService {

    private final ByteBuddyTransformerManager manager;
    private final BytecodeSnapshotRepository snapshotRepository;
    private final TransformationJournal journal;

    public BytecodeCaptureService(ByteBuddyTransformerManager manager,
                                  BytecodeSnapshotRepository snapshotRepository,
                                  TransformationJournal journal) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.snapshotRepository = Objects.requireNonNull(snapshotRepository, "snapshotRepository");
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    public CaptureResult capture(Class<?> clazz) {
        Objects.requireNonNull(clazz, "clazz");
        ClassIdentity identity = ClassIdentities.of(clazz);
        TransformationRevision revision = journal.currentRevision(identity);
        long now = System.currentTimeMillis();
        byte[] applied = manager.captureApplied(clazz);
        if (applied == null) {
            TransformationDiagnostic diagnostic = TransformationDiagnostic.error("CAPTURE_FAILED",
                    "could not capture applied bytes for " + identity.binaryClassName()
                            + " (class not modifiable or retransform failed)");
            journal.recordVerification(identity, revision, false, List.of(diagnostic), now, 0L);
            return new CaptureResult(identity, revision, null, null, List.of(diagnostic), now, false);
        }
        String appliedHash = BytecodeHash.sha256Hex(applied);
        BytecodeSnapshotKey key = new BytecodeSnapshotKey(identity, revision, BytecodeSnapshotKind.APPLIED);
        BytecodeSnapshotMetadata metadata = new BytecodeSnapshotMetadata(identity, revision,
                BytecodeSnapshotKind.APPLIED, appliedHash, applied.length, now, "capture",
                "actual bytes re-read from the JVM");
        try {
            snapshotRepository.store(key, applied, metadata);
        } catch (RuntimeException ignored) {
            // storage failure does not invalidate the captured bytes
        }
        List<TransformationDiagnostic> diagnostics = verifyAgainstLastOutput(identity, appliedHash);
        boolean verified = diagnostics.isEmpty();
        journal.recordVerification(identity, revision, verified,
                verified ? List.of() : diagnostics, now, 0L);
        return new CaptureResult(identity, revision, appliedHash, applied, diagnostics, now, true);
    }

    private List<TransformationDiagnostic> verifyAgainstLastOutput(ClassIdentity identity, String appliedHash) {
        List<TransformationResult> history = journal.history(identity);
        for (int i = history.size() - 1; i >= 0; i--) {
            TransformationResult entry = history.get(i);
            if (entry.status() == TransformationStatus.SUCCEEDED && entry.outputHash() != null) {
                if (!entry.outputHash().equals(appliedHash)) {
                    List<TransformationDiagnostic> diagnostics = new ArrayList<>();
                    diagnostics.add(TransformationDiagnostic.warn("APPLIED_DIFFERS_FROM_OUTPUT",
                            "applied bytes differ from the last recorded transform output; another "
                                    + "transformer may have run after Kairo"));
                    return diagnostics;
                }
                return List.of();
            }
        }
        return List.of();
    }

    public record CaptureResult(ClassIdentity classIdentity,
                                TransformationRevision revision,
                                String appliedHash,
                                byte[] appliedBytes,
                                List<TransformationDiagnostic> diagnostics,
                                long capturedAtMillis,
                                boolean captured) {
    }
}
