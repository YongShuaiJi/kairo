package com.example.kairo.agent.core.bytecode;

import com.example.kairo.agent.core.InstrumentationRegistry;
import com.example.kairo.agent.core.TransformationPlan;
import com.example.kairo.api.bytecode.BytecodeSnapshotKind;
import com.example.kairo.api.bytecode.BytecodeSnapshotMetadata;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.TransformationDiagnostic;
import com.example.kairo.api.bytecode.TransformationRevision;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.pool.TypePool;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only preview of what the agent <em>would</em> weave, without touching the
 * running JVM. The preview builds the same {@link TransformationPlan} the real
 * {@code KairoTransformer} uses and applies it to the input bytes via an offline
 * {@code ByteBuddy.redefine}, so the planned output is produced by the exact same
 * Advice weaving logic as a real transformation.
 *
 * <p>Preview never calls {@code Instrumentation.retransformClasses} and never advances
 * a transformation revision. It may store a {@code PLANNED} snapshot at the current
 * revision for later diffing. It must be invoked from a control or diagnostic thread,
 * never a business thread.
 */
public final class TransformationPreviewService {

    private final InstrumentationRegistry registry;
    private final BytecodeSnapshotRepository snapshotRepository;
    private final TransformationJournal journal;
    private final ByteBuddy byteBuddy = new ByteBuddy();

    public TransformationPreviewService(InstrumentationRegistry registry,
                                        BytecodeSnapshotRepository snapshotRepository,
                                        TransformationJournal journal) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.snapshotRepository = snapshotRepository;
        this.journal = journal;
    }

    public PreviewResult preview(ClassIdentity identity, byte[] inputBytes) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(inputBytes, "inputBytes");
        String inputHash = BytecodeHash.sha256Hex(inputBytes);
        TransformationRevision revision = currentRevision(identity);
        TransformationPlan plan = TransformationPlan.from(registry, identity.binaryClassName(),
                identity.classLoaderId());
        if (plan.isEmpty()) {
            return new PreviewResult(identity, revision, inputHash, inputHash, inputBytes.clone(),
                    0, Set.of(), List.of(), false);
        }
        try {
            ClassFileLocator locator = new ClassFileLocator.Compound(
                    locatorFor(identity.binaryClassName(), inputBytes),
                    ClassFileLocator.ForClassLoader.ofSystemLoader());
            TypeDescription typeDescription = TypePool.Default.of(locator)
                    .describe(identity.binaryClassName()).resolve();
            DynamicType dynamicType = plan.apply(byteBuddy.redefine(typeDescription, locator)).make();
            byte[] plannedBytes = dynamicType.getBytes();
            String plannedHash = BytecodeHash.sha256Hex(plannedBytes);
            boolean changed = !java.util.Arrays.equals(inputBytes, plannedBytes);
            storePlanned(identity, revision, plannedHash, plannedBytes);
            return new PreviewResult(identity, revision, inputHash, plannedHash, plannedBytes,
                    plan.targetMethodCount(), plan.adviceTypes(), List.of(), changed);
        } catch (RuntimeException e) {
            TransformationDiagnostic diagnostic = TransformationDiagnostic.error("PREVIEW_FAILED",
                    "could not build planned bytes for " + identity.binaryClassName(), e);
            return new PreviewResult(identity, revision, inputHash, null, null,
                    plan.targetMethodCount(), plan.adviceTypes(), List.of(diagnostic), false);
        }
    }

    /**
     * Build a ClassFileLocator that serves the input bytes under the binary, internal
     * and resource name forms. Byte Buddy's {@code TypePool.describe} and
     * {@code redefine().make()} may ask for the class by any of these forms depending
     * on how the {@code TypeDescription} was resolved; serving all three keeps the
     * offline preview robust without depending on Byte Buddy internals.
     */
    private static ClassFileLocator locatorFor(String binaryClassName, byte[] inputBytes) {
        String internal = binaryClassName.replace('.', '/');
        java.util.Map<String, byte[]> storage = new java.util.HashMap<>();
        storage.put(binaryClassName, inputBytes);
        storage.put(internal, inputBytes);
        storage.put(internal + ".class", inputBytes);
        return new ClassFileLocator.Simple(storage);
    }

    private void storePlanned(ClassIdentity identity, TransformationRevision revision,
                              String plannedHash, byte[] plannedBytes) {
        if (snapshotRepository == null || plannedBytes == null) {
            return;
        }
        try {
            BytecodeSnapshotKey key = new BytecodeSnapshotKey(identity, revision, BytecodeSnapshotKind.PLANNED);
            BytecodeSnapshotMetadata metadata = new BytecodeSnapshotMetadata(identity, revision,
                    BytecodeSnapshotKind.PLANNED, plannedHash, plannedBytes.length,
                    System.currentTimeMillis(), "preview", "read-only planned output");
            snapshotRepository.store(key, plannedBytes, metadata);
        } catch (RuntimeException ignored) {
            // snapshot storage failures must not surface from preview
        }
    }

    private TransformationRevision currentRevision(ClassIdentity identity) {
        return journal == null ? TransformationRevision.INITIAL : journal.currentRevision(identity);
    }

    /**
     * @param plannedBytes the planned output bytes; {@code null} when preview failed
     * @param plannedHash  sha-256 hex of {@code plannedBytes}; {@code null} when preview failed
     */
    public record PreviewResult(ClassIdentity classIdentity,
                                TransformationRevision revision,
                                String inputHash,
                                String plannedHash,
                                byte[] plannedBytes,
                                int targetMethodCount,
                                Set<String> adviceTypes,
                                List<TransformationDiagnostic> diagnostics,
                                boolean changed) {
    }
}
