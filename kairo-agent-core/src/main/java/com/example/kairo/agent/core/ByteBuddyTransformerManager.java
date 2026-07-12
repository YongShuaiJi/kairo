package com.example.kairo.agent.core;

import com.example.kairo.agent.core.bytecode.BytecodeHash;
import com.example.kairo.agent.core.bytecode.BytecodeSnapshotKey;
import com.example.kairo.agent.core.bytecode.BytecodeSnapshotRepository;
import com.example.kairo.agent.core.bytecode.ClassIdentities;
import com.example.kairo.agent.core.bytecode.TransformationJournal;
import com.example.kairo.agent.core.bytecode.TransformationListener;
import com.example.kairo.api.bytecode.BytecodeSnapshotKind;
import com.example.kairo.api.bytecode.BytecodeSnapshotMetadata;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.TransformationDiagnostic;
import com.example.kairo.api.bytecode.TransformationResult;
import com.example.kairo.api.bytecode.TransformationRevision;
import com.example.kairo.api.bytecode.TransformationStatus;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Installs and drives the Kairo class transformer.
 *
 * <p>V1.1 rework, preserving V1.0 weaving behaviour (same {@link KairoTransformer}
 * via {@link TransformationPlan}, same ignore rules, retransformation strategy and
 * reset semantics) while adding the bytecode-visibility foundation:
 * <ul>
 *   <li>a pass-through {@link InputCaptureTransformer} installed ahead of Kairo
 *       records the INPUT bytes Kairo actually received and opens a journal entry;</li>
 *   <li>an {@link AgentBuilder.Listener} records the OUTPUT hash, closes the entry
 *       and emits a structured per-class {@link TransformationResult};</li>
 *   <li>each real transformation advances the per-class monotonic revision held by
 *       {@link TransformationJournal}; read-only preview and capture never advance it;</li>
 *   <li>{@link #captureApplied(Class)} re-reads the bytes actually running in the JVM
 *       through a short-lived capturing transformer, without overwriting other
 *       agents' output or treating any bytes as the "original".</li>
 * </ul>
 *
 * <p>Recording is gated by a {@link Mode} so that initial-load weaving (V1.0 still
 * weaves on first load) and capture-driven retransforms do not produce spurious
 * revisions or journal entries.
 */
public final class ByteBuddyTransformerManager implements AutoCloseable {

    private enum Mode {
        /** No explicit retransform in progress: weave silently, record nothing. */
        IDLE,
        /** An explicit {@link #retransform(Class[])} is in progress: record INPUT/OUTPUT. */
        RETRANSFORM,
        /** A capture is in progress: weave but record nothing; the capture transformer records. */
        CAPTURE
    }

    private final Instrumentation instrumentation;
    private final InstrumentationRegistry registry;
    private final BytecodeSnapshotRepository snapshotRepository;
    private final TransformationJournal journal;
    private final List<TransformationListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong retransformCount = new AtomicLong();
    private final Set<ClassIdentity> affectedClasses = ConcurrentHashMap.newKeySet();
    private final List<ForeignTransformerProbe> foreignProbes = new CopyOnWriteArrayList<>();
    // Thread-local: retransform/capture state is visible only to the calling thread,
    // and removed in every finally/close so pooled threads never retain it.
    private final ThreadLocal<Mode> currentMode = ThreadLocal.withInitial(() -> Mode.IDLE);
    private final ThreadLocal<List<TransformationResult>> currentResults = ThreadLocal.withInitial(ArrayList::new);
    private final ThreadLocal<TransformContext> currentContext = new ThreadLocal<>();


    private ResettableClassFileTransformer transformer;
    private ClassFileTransformer inputCapture;

    public ByteBuddyTransformerManager(Instrumentation instrumentation, InstrumentationRegistry registry) {
        this(instrumentation, registry, null, null);
    }

    public ByteBuddyTransformerManager(Instrumentation instrumentation,
                                       InstrumentationRegistry registry,
                                       BytecodeSnapshotRepository snapshotRepository,
                                       TransformationJournal journal) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.snapshotRepository = snapshotRepository;
        this.journal = journal;
    }

    public synchronized void install() {
        if (transformer != null) {
            return;
        }
        inputCapture = new InputCaptureTransformer();
        instrumentation.addTransformer(inputCapture, true);
        transformer = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .ignore(this::ignore)
                .type(this::matchesRegisteredType)
                .transform(new KairoTransformer(registry))
                .with(new KairoListener())
                .installOn(instrumentation);
    }

    /**
     * Retransform the given classes and return a structured per-class result.
     *
     * <p>Source-compatible with the V1.0 {@code void} return: callers that ignore the
     * result are unaffected. Classes that are not modifiable are skipped.
     */
    public List<TransformationResult> retransform(Class<?>... classes) {
        Objects.requireNonNull(classes, "classes");
        Class<?>[] modifiable = Arrays.stream(classes)
                .filter(Objects::nonNull)
                .filter(instrumentation::isModifiableClass)
                .toArray(Class<?>[]::new);
        if (modifiable.length == 0) {
            return List.of();
        }
        synchronized (this) {
            List<TransformationResult> results = currentResults.get();
            results.clear();
            currentMode.set(Mode.RETRANSFORM);
            try {
                instrumentation.retransformClasses(modifiable);
                retransformCount.incrementAndGet();
            } catch (UnmodifiableClassException e) {
                recordRetransformFailure(modifiable, "CLASS_UNMODIFIABLE", "class is not retransformable", e);
            } catch (RuntimeException e) {
                recordRetransformFailure(modifiable, "RETRANSFORM_FAILED", "retransformClasses threw", e);
            } finally {
                abandonOrphanedContext();
                currentMode.remove();
                currentContext.remove();
                currentResults.remove();
            }
            return List.copyOf(results);
        }
    }

    /**
     * Re-read the bytes actually running in the JVM for {@code clazz} via a short-lived
     * capturing transformer. The class is retransformed so the JVM re-runs the transformer
     * chain from the original class file; Kairo re-weaves deterministically (no new revision)
     * and the capturing transformer, installed last, observes the final applied bytes.
     *
     * @return the captured bytes, or {@code null} if the class cannot be retransformed
     */
    public byte[] captureApplied(Class<?> clazz) {
        Objects.requireNonNull(clazz, "clazz");
        if (!instrumentation.isModifiableClass(clazz)) {
            return null;
        }
        synchronized (this) {
            currentMode.set(Mode.CAPTURE);
            CaptureOnlyTransformer capture = new CaptureOnlyTransformer(clazz);
            instrumentation.addTransformer(capture, true);
            try {
                instrumentation.retransformClasses(clazz);
            } catch (UnmodifiableClassException e) {
                return null;
            } catch (RuntimeException e) {
                return null;
            } finally {
                instrumentation.removeTransformer(capture);
                currentMode.remove();
                currentContext.remove();
                currentResults.remove();
            }
            return capture.capturedBytes();
        }
    }

    public long retransformCount() {
        return retransformCount.get();
    }

    public BytecodeSnapshotRepository snapshotRepository() {
        return snapshotRepository;
    }

    public TransformationJournal journal() {
        return journal;
    }

    public void addListener(TransformationListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public boolean removeListener(TransformationListener listener) {
        return listeners.remove(listener);
    }

    public TransformationRevision currentRevision(ClassIdentity classIdentity) {
        return journal == null ? TransformationRevision.INITIAL : journal.currentRevision(classIdentity);
    }

    // -------------------------------------------------------- V1.4 coexistence

    /**
     * Register a foreign (non-Kairo) transformer probe for the coexistence test
     * matrix. The probe describes whether the foreign transformer is installed
     * ahead of Kairo and whether it is safe to re-run (idempotent). Retransform
     * re-runs every installed transformer on the original bytes; a non-idempotent
     * foreign transformer ahead of Kairo cannot be safely re-run, so
     * {@link #coexistenceUnsafe(Class)} reports it and the chain applier returns
     * {@code COEXISTENCE_UNSAFE} rather than corrupting the class.
     */
    public void registerForeignProbe(ForeignTransformerProbe probe) {
        if (probe != null) {
            foreignProbes.add(probe);
        }
    }

    public boolean unregisterForeignProbe(ForeignTransformerProbe probe) {
        return foreignProbes.remove(probe);
    }

    /**
     * Returns a non-null reason when retransforming {@code clazz} cannot be
     * safely ordered against a foreign transformer, or {@code null} when
     * coexistence is safe. Production deployments register no probes, so this
     * returns {@code null} (Kairo is the only transformer).
     */
    public String coexistenceUnsafe(Class<?> clazz) {
        for (ForeignTransformerProbe probe : foreignProbes) {
            if (!probe.idempotent() && probe.installedAheadOfKairo()) {
                return "foreign transformer " + probe.name() + " is installed ahead of Kairo and is not "
                        + "idempotent; retransform order cannot be guaranteed";
            }
            if (probe.installedAheadOfKairo() && !probe.supportsRetransform()) {
                return "foreign transformer " + probe.name() + " does not support retransformation";
            }
        }
        return null;
    }

    /**
     * Whether any foreign probe is installed (ahead or behind Kairo). Used by
     * tests to assert foreign markers are present and preserved.
     */
    public boolean hasForeignProbes() {
        return !foreignProbes.isEmpty();
    }

    /**
     * Record a recovery entry in the transformation journal for a class whose
     * Kairo advice has been removed by a precise per-class retransform (e.g.
     * {@code resetAll}). This is the V1.4 replacement for the recovery entries
     * the old {@code transformer.reset} path wrote during {@code close()}.
     */
    public void recordRecovery(Class<?> clazz, String reason) {
        if (journal == null) {
            return;
        }
        ClassIdentity identity = ClassIdentities.of(clazz);
        long now = System.currentTimeMillis();
        journal.recordRecovery(identity, journal.currentRevision(identity), reason, now);
        affectedClasses.remove(identity);
    }

    @Override
    public synchronized void close() {
        currentMode.remove();
        currentContext.remove();
        currentResults.remove();
        if (inputCapture != null) {
            instrumentation.removeTransformer(inputCapture);
            inputCapture = null;
        }
        if (transformer != null) {
            transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
            transformer = null;
        }
        if (journal != null) {
            long now = System.currentTimeMillis();
            for (ClassIdentity identity : affectedClasses) {
                journal.recordRecovery(identity, journal.currentRevision(identity),
                        "transformer reset", now);
            }
        }
        affectedClasses.clear();
    }

    private void recordRetransformFailure(Class<?>[] classes, String code, String message, Throwable cause) {
        long now = System.currentTimeMillis();
        for (Class<?> clazz : classes) {
            ClassIdentity identity = ClassIdentities.of(clazz);
            TransformationRevision revision = journal == null
                    ? TransformationRevision.INITIAL : journal.currentRevision(identity);
            TransformationDiagnostic diagnostic = TransformationDiagnostic.error(code, message, cause);
            if (journal != null) {
                journal.recordFailure(identity, revision, null, List.of(diagnostic), now, 0L);
            }
            TransformationResult result = new TransformationResult(identity, revision,
                    TransformationStatus.FAILED, null, null, List.of(diagnostic), now, 0L);
            currentResults.get().add(result);
            notifyListeners(result);
        }
    }

    private void notifyListeners(TransformationResult result) {
        for (TransformationListener listener : listeners) {
            try {
                listener.onTransformation(result);
            } catch (RuntimeException ignored) {
                // a listener must never break the transformation pipeline
            }
        }
    }

    private void abandonOrphanedContext() {
        TransformContext ctx = currentContext.get();
        if (ctx == null) {
            return;
        }
        // InputCapture opened a context but no matching onTransformation/onError closed it.
        if (journal != null) {
            journal.recordFailure(ctx.classIdentity, ctx.revision, ctx.inputHash,
                    List.of(TransformationDiagnostic.warn("TRANSFORM_INCOMPLETE",
                            "input captured but transformation did not complete")),
                    ctx.startedAt, System.currentTimeMillis() - ctx.startedAt);
        }
        currentContext.remove();
    }

    private boolean matchesRegisteredType(TypeDescription typeDescription, ClassLoader classLoader,
                                          JavaModule module, Class<?> classBeingRedefined,
                                          ProtectionDomain protectionDomain) {
        return registry.containsType(typeDescription.getName(), classLoader);
    }

    private boolean ignore(TypeDescription typeDescription, ClassLoader classLoader,
                           JavaModule module, Class<?> classBeingRedefined,
                           ProtectionDomain protectionDomain) {
        return isIgnored(typeDescription.getName());
    }

    private static boolean isIgnored(String binaryName) {
        return binaryName.startsWith("java.")
                || binaryName.startsWith("javax.")
                || binaryName.startsWith("jdk.")
                || binaryName.startsWith("sun.")
                || binaryName.startsWith("com.sun.")
                || binaryName.startsWith("com.oracle.")
                || binaryName.startsWith("org.w3c.dom.")
                || binaryName.startsWith("org.xml.sax.")
                || binaryName.startsWith("org.ietf.jgss.")
                || binaryName.startsWith("net.bytebuddy.")
                || binaryName.startsWith("groovy.")
                || binaryName.startsWith("org.codehaus.groovy.")
                || binaryName.startsWith("com.example.kairo.");
    }

    // ---- transform pipeline callbacks (run on the retransform thread) ----

    private void onInputCaptured(String internalName, ClassLoader loader, byte[] buffer) {
        if (currentMode.get() != Mode.RETRANSFORM || buffer == null || journal == null || snapshotRepository == null) {
            return;
        }
        String binaryName = internalName.replace('/', '.');
        if (isIgnored(binaryName) || !registry.containsType(binaryName, loader)) {
            return;
        }
        ClassIdentity identity = ClassIdentities.of(binaryName, loader);
        TransformationRevision revision = journal.nextRevision(identity);
        String inputHash = BytecodeHash.sha256Hex(buffer);
        long now = System.currentTimeMillis();
        try {
            BytecodeSnapshotKey key = new BytecodeSnapshotKey(identity, revision, BytecodeSnapshotKind.INPUT);
            BytecodeSnapshotMetadata metadata = new BytecodeSnapshotMetadata(identity, revision,
                    BytecodeSnapshotKind.INPUT, inputHash, buffer.length, now, "jvm", "transformer input");
            snapshotRepository.store(key, buffer, metadata);
        } catch (RuntimeException ignored) {
            // snapshot storage must never break transformation
        }
        journal.recordStart(identity, revision, inputHash, now);
        affectedClasses.add(identity);
        currentContext.set(new TransformContext(identity, revision, inputHash, now));
    }

    private void onTransformed(TypeDescription typeDescription, ClassLoader loader, DynamicType dynamicType) {
        if (currentMode.get() != Mode.RETRANSFORM) {
            return;
        }
        TransformContext ctx = currentContext.get();
        if (ctx == null) {
            return;
        }
        byte[] output = dynamicType.getBytes();
        String outputHash = BytecodeHash.sha256Hex(output);
        long now = System.currentTimeMillis();
        long duration = now - ctx.startedAt;
        if (journal != null) {
            journal.recordSuccess(ctx.classIdentity, ctx.revision, ctx.inputHash, outputHash, ctx.startedAt, duration);
        }
        TransformationResult result = new TransformationResult(ctx.classIdentity, ctx.revision,
                TransformationStatus.SUCCEEDED, ctx.inputHash, outputHash, List.of(), ctx.startedAt, duration);
        currentResults.get().add(result);
        notifyListeners(result);
        currentContext.remove();
    }

    private void onTransformFailed(String internalName, ClassLoader loader, Throwable cause) {
        if (currentMode.get() != Mode.RETRANSFORM) {
            return;
        }
        TransformContext ctx = currentContext.get();
        long now = System.currentTimeMillis();
        ClassIdentity identity = ctx != null ? ctx.classIdentity
                : (loader != null || internalName != null
                        ? ClassIdentities.of(internalName.replace('/', '.'), loader) : null);
        if (identity == null) {
            return;
        }
        TransformationRevision revision = ctx != null ? ctx.revision
                : (journal != null ? journal.currentRevision(identity) : TransformationRevision.INITIAL);
        TransformationDiagnostic diagnostic = TransformationDiagnostic.error("TRANSFORM_FAILED",
                "Byte Buddy could not transform " + identity.binaryClassName(), cause);
        long startedAt = ctx != null ? ctx.startedAt : now;
        if (journal != null) {
            journal.recordFailure(identity, revision, ctx != null ? ctx.inputHash : null,
                    List.of(diagnostic), startedAt, now - startedAt);
        }
        TransformationResult result = new TransformationResult(identity, revision,
                TransformationStatus.FAILED, ctx != null ? ctx.inputHash : null, null,
                List.of(diagnostic), startedAt, now - startedAt);
        currentResults.get().add(result);
        notifyListeners(result);
        currentContext.remove();
    }

    private void handleIgnored() {
        if (currentMode.get() == Mode.RETRANSFORM) {
            currentContext.remove();
        }
    }

    private static final class TransformContext {
        final ClassIdentity classIdentity;
        final TransformationRevision revision;
        final String inputHash;
        final long startedAt;

        TransformContext(ClassIdentity classIdentity, TransformationRevision revision,
                         String inputHash, long startedAt) {
            this.classIdentity = classIdentity;
            this.revision = revision;
            this.inputHash = inputHash;
            this.startedAt = startedAt;
        }
    }

    /**
     * Pass-through transformer installed ahead of Kairo. It records the INPUT bytes
     * (whatever the JVM handed to the chain this time) and returns {@code null} so the
     * bytes reach Kairo unchanged.
     */
    private final class InputCaptureTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer)
                throws IllegalClassFormatException {
            try {
                onInputCaptured(name, loader, classfileBuffer);
            } catch (RuntimeException ignored) {
                // capture must never interfere with the real transformation
            }
            return null;
        }
    }

    /**
     * Short-lived transformer installed last during a capture. It never modifies bytes;
     * it only records the final bytes observed for the target class.
     */
    private static final class CaptureOnlyTransformer implements ClassFileTransformer {
        private final Class<?> target;
        private volatile byte[] captured;

        CaptureOnlyTransformer(Class<?> target) {
            this.target = target;
        }

        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (classBeingRedefined == target && classfileBuffer != null) {
                captured = classfileBuffer.clone();
            }
            return null;
        }

        byte[] capturedBytes() {
            return captured;
        }
    }

    private final class KairoListener extends AgentBuilder.Listener.Adapter {
        @Override
        public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                     JavaModule module, boolean loaded, DynamicType dynamicType) {
            try {
                onTransformed(typeDescription, classLoader, dynamicType);
            } catch (RuntimeException ignored) {
                // listener failures must not break transformation
            }
        }

        @Override
        public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                            boolean loaded, Throwable throwable) {
            try {
                onTransformFailed(typeName, classLoader, throwable);
            } catch (RuntimeException ignored) {
                // listener failures must not break transformation
            }
        }

        @Override
        public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader,
                              JavaModule module, boolean loaded) {
            handleIgnored();
        }
    }
}
