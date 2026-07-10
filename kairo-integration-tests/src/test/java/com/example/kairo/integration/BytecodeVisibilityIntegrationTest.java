package com.example.kairo.integration;

import com.example.kairo.agent.core.MethodSignature;
import com.example.kairo.agent.core.bytecode.BytecodeHash;
import com.example.kairo.agent.core.bytecode.ClassIdentities;
import com.example.kairo.api.bytecode.BytecodeDiffResult;
import com.example.kairo.api.bytecode.BytecodeSnapshotKind;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.TransformationResult;
import com.example.kairo.api.bytecode.TransformationStatus;
import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.MockRule;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.core.MethodDescriptor;
import com.example.demo.OrderService;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.jar.asm.Attribute;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.ByteVector;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for the V1.1 bytecode-visibility foundation, using a real
 * {@link Instrumentation}. Exercises preview (no JVM effect), the four-hash lifecycle
 * (before/preview/applied/unload), structured instruction diff, enhancement and reset
 * history, failure diagnostics, dual-ClassLoader isolation and test-transformer
 * coexistence.
 */
class BytecodeVisibilityIntegrationTest {

    private AgentRuntime runtime;
    private Instrumentation instrumentation;

    @BeforeEach
    void setUp() {
        instrumentation = ByteBuddyAgent.install();
        runtime = new AgentRuntime(instrumentation);
        runtime.start();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void previewProducesPlannedBytesWithoutTouchingTheJvm() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        ClassIdentity identity = ClassIdentities.of(OrderService.class);
        byte[] baseline = runtime.captureService().capture(OrderService.class).appliedBytes();
        assertThat(baseline).isNotNull();

        // Register the method so the plan matches it, but do NOT retransform: the JVM
        // must stay un-enhanced while the preview weaves offline.
        runtime.instrumentationRegistry().register(signatureOf(method));
        try {
            assertThat(new OrderService().calculateScore(10)).isEqualTo(20);

            var preview = runtime.previewService().preview(identity, baseline);
            assertThat(preview.changed()).isTrue();
            assertThat(preview.plannedBytes()).isNotEmpty();
            assertThat(preview.targetMethodCount()).isEqualTo(1);
            assertThat(preview.adviceTypes()).contains("VALUE");
            assertThat(preview.plannedHash()).isNotEqualTo(preview.inputHash());

            // JVM behaviour is unchanged: preview never retransformed.
            assertThat(new OrderService().calculateScore(10)).isEqualTo(20);

            BytecodeDiffResult diff = runtime.diffService().diff(identity, baseline,
                    preview.revision(), BytecodeSnapshotKind.INPUT,
                    preview.plannedBytes(), preview.revision(), BytecodeSnapshotKind.PLANNED);
            assertThat(diff.identical()).isFalse();
            assertThat(diff.methodDiffs()).extracting(m -> m.methodName() + m.methodDescriptor())
                    .contains("calculateScore(I)I");
            BytecodeDiffResult.MethodDiff methodDiff = diff.methodDiffs().stream()
                    .filter(m -> m.methodName().equals("calculateScore")).findFirst().orElseThrow();
            assertThat(methodDiff.changeType()).isEqualTo(BytecodeDiffResult.ChangeType.MODIFIED);
            String instructions = String.join("\n", methodDiff.instructionDiffs());
            assertThat(instructions).contains("KairoBridge");
        } finally {
            runtime.instrumentationRegistry().unregister(signatureOf(method));
        }
    }

    @Test
    void fourHashesBeforePreviewAppliedAndUnloaded() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        ClassIdentity identity = ClassIdentities.of(OrderService.class);
        byte[] baseline = runtime.captureService().capture(OrderService.class).appliedBytes();
        String beforeHash = BytecodeHash.sha256Hex(baseline);

        // Apply a real rule.
        runtime.publish(method, rule("lifecycle", method, InvokePhase.BEFORE,
                "return mock.returnValue(999)"));
        assertThat(new OrderService().calculateScore(1)).isEqualTo(999);

        var preview = runtime.previewService().preview(identity, baseline);
        String plannedHash = preview.plannedHash();
        String appliedHash = runtime.captureService().capture(OrderService.class).appliedHash();

        assertThat(appliedHash).isNotEqualTo(beforeHash);
        // Preview and real apply weave the same original bytes with the same plan.
        BytecodeDiffResult plannedVsApplied = runtime.diffService().diff(identity,
                preview.plannedBytes(), preview.revision(), BytecodeSnapshotKind.PLANNED,
                runtime.captureService().capture(OrderService.class).appliedBytes(),
                preview.revision(), BytecodeSnapshotKind.APPLIED);
        assertThat(plannedVsApplied.identical()).as(plannedVsApplied::summary).isTrue();

        // Unload the rule: retransform restores the original bytes.
        runtime.remove(method, "lifecycle");
        String afterUnloadHash = runtime.captureService().capture(OrderService.class).appliedHash();
        assertThat(afterUnloadHash).isEqualTo(beforeHash);
        assertThat(new OrderService().calculateScore(1)).isEqualTo(2);
    }

    @Test
    void enhancementAndResetAreRecordedInJournal() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        ClassIdentity identity = ClassIdentities.of(OrderService.class);

        runtime.publish(method, rule("history", method, InvokePhase.BEFORE,
                "return mock.returnValue(7)"));

        List<TransformationResult> history = runtime.transformationJournal().history(identity);
        assertThat(history).extracting(TransformationResult::status)
                .contains(TransformationStatus.STARTED, TransformationStatus.SUCCEEDED);
        TransformationResult success = history.stream()
                .filter(r -> r.status() == TransformationStatus.SUCCEEDED).findFirst().orElseThrow();
        assertThat(success.inputHash()).isNotBlank();
        assertThat(success.outputHash()).isNotBlank();
        assertThat(success.outputHash()).isNotEqualTo(success.inputHash());
        assertThat(runtime.transformationJournal().currentRevision(identity).value()).isEqualTo(1L);

        runtime.resetAll("test");

        List<TransformationResult> afterReset = runtime.transformationJournal().history(identity);
        assertThat(afterReset).extracting(TransformationResult::status)
                .contains(TransformationStatus.RECOVERED);
        assertThat(new OrderService().calculateScore(5)).isEqualTo(10);
    }

    @Test
    void retransformReturnsPerClassResults() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        ClassIdentity identity = ClassIdentities.of(OrderService.class);
        runtime.instrumentationRegistry().register(signatureOf(method));
        try {
            List<TransformationResult> results = runtime.transformerManager().retransform(OrderService.class);
            assertThat(results).hasSize(1);
            assertThat(results.get(0).status()).isEqualTo(TransformationStatus.SUCCEEDED);
            assertThat(results.get(0).classIdentity()).isEqualTo(identity);
            assertThat(results.get(0).outputHash()).isNotBlank();

            // Unregister and retransform: Kairo no longer matches, so no result is recorded.
            runtime.instrumentationRegistry().unregister(signatureOf(method));
            List<TransformationResult> noop = runtime.transformerManager().retransform(OrderService.class);
            assertThat(noop).isEmpty();
        } finally {
            runtime.instrumentationRegistry().unregister(signatureOf(method));
        }
    }

    @Test
    void failureDiagnosticsAreStructured() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        ClassIdentity identity = ClassIdentities.of(OrderService.class);
        // Register the method so the plan is non-empty; the invalid bytes must then
        // fail during offline redefine, producing a structured PREVIEW_FAILED diagnostic.
        runtime.instrumentationRegistry().register(signatureOf(method));
        try {
            var preview = runtime.previewService().preview(identity, new byte[]{0x00, (byte) 0xCA, (byte) 0xFE});
            assertThat(preview.changed()).isFalse();
            assertThat(preview.plannedBytes()).isNull();
            assertThat(preview.diagnostics()).isNotEmpty();
            assertThat(preview.diagnostics().get(0).code()).isEqualTo("PREVIEW_FAILED");
        } finally {
            runtime.instrumentationRegistry().unregister(signatureOf(method));
        }

        // Primitives are not modifiable, so capture must report a structured failure.
        var capture = runtime.captureService().capture(int.class);
        assertThat(capture.captured()).isFalse();
        assertThat(capture.diagnostics()).extracting(d -> d.code()).contains("CAPTURE_FAILED");
    }

    @Test
    void sameClassNameInDifferentClassLoadersKeepsIndependentRevisions() throws Exception {
        Class<?> first = compileAndLoadDuplicateService("first");
        Class<?> second = compileAndLoadDuplicateService("second");
        Method firstEcho = first.getMethod("echo", String.class);
        Method secondEcho = second.getMethod("echo", String.class);
        Object firstInstance = first.getDeclaredConstructor().newInstance();
        Object secondInstance = second.getDeclaredConstructor().newInstance();

        runtime.publish(firstEcho, rule("iso", firstEcho, InvokePhase.RETURN,
                "return mock.returnValue('mocked')"));

        ClassIdentity firstIdentity = ClassIdentities.of(first);
        ClassIdentity secondIdentity = ClassIdentities.of(second);
        assertThat(firstIdentity).isNotEqualTo(secondIdentity);
        assertThat(runtime.transformationJournal().currentRevision(firstIdentity).value()).isEqualTo(1L);
        assertThat(runtime.transformationJournal().currentRevision(secondIdentity).value()).isZero();
        assertThat(runtime.transformationJournal().history(secondIdentity)).isEmpty();

        assertThat(firstEcho.invoke(firstInstance, "x")).isEqualTo("mocked");
        assertThat(secondEcho.invoke(secondInstance, "x")).isEqualTo("second-x");

        runtime.publish(secondEcho, rule("iso-second", secondEcho, InvokePhase.RETURN,
                "return mock.returnValue('mocked-second')"));
        assertThat(runtime.transformationJournal().currentRevision(secondIdentity).value()).isEqualTo(1L);

        runtime.resetAll("dual-loader-recovery");
        assertThat(runtime.transformationJournal().history(firstIdentity))
                .extracting(TransformationResult::status).contains(TransformationStatus.RECOVERED);
        assertThat(runtime.transformationJournal().history(secondIdentity))
                .extracting(TransformationResult::status).contains(TransformationStatus.RECOVERED);
    }

    @Test
    void testTransformersBeforeAndAfterKairoRespectInputAppliedSemantics() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        ClassIdentity identity = ClassIdentities.of(OrderService.class);
        String internal = OrderService.class.getName().replace('.', '/');

        // The runtime already installed InputCapture + Kairo during start(). Install a
        // pre-Kairo marker by reordering: remove our transformers is not possible here,
        // so we instead install an AFTER marker and verify APPLIED reflects it while the
        // INPUT snapshot (captured before Kairo) does not.
        AttributeAddingTransformer afterMarker =
                new AttributeAddingTransformer("KairoAfterMarker", internal);
        instrumentation.addTransformer(afterMarker, true);
        try {
            runtime.publish(method, rule("coexist", method, InvokePhase.BEFORE,
                    "return mock.returnValue(42)"));

            // INPUT snapshot stored during the publish retransform: captured by InputCapture
            // ahead of Kairo, so it must not carry the post-Kairo marker.
            var inputMeta = runtime.snapshotRepository().metadataFor(identity).stream()
                    .filter(m -> m.kind() == BytecodeSnapshotKind.INPUT)
                    .findFirst().orElseThrow();
            byte[] inputBytes = runtime.snapshotRepository().bytes(
                    new com.example.kairo.agent.core.bytecode.BytecodeSnapshotKey(
                            identity, inputMeta.revision(), BytecodeSnapshotKind.INPUT)).orElseThrow();
            assertThat(containsUtf8(inputBytes, "KairoAfterMarker")).isFalse();

            byte[] applied = runtime.captureService().capture(OrderService.class).appliedBytes();
            assertThat(applied).isNotNull();
            assertThat(containsUtf8(applied, "KairoAfterMarker")).isTrue();
            assertThat(BytecodeHash.sha256Hex(applied))
                    .isNotEqualTo(BytecodeHash.sha256Hex(inputBytes));
        } finally {
            instrumentation.removeTransformer(afterMarker);
            runtime.remove(method, "coexist");
        }
    }

    @Test
    void decompilerIsUnavailableWithClearDiagnostic() throws Exception {
        ClassIdentity identity = ClassIdentities.of(OrderService.class);
        byte[] bytes = runtime.captureService().capture(OrderService.class).appliedBytes();
        var result = runtime.decompilerService().decompile(identity, bytes);
        assertThat(result.status()).isEqualTo(com.example.kairo.api.bytecode.DecompilationStatus.UNAVAILABLE);
        assertThat(result.sourceCode()).isNull();
        assertThat(result.diagnostics()).anyMatch(s -> s.contains("No Java decompiler"));
    }

    @Test
    void snapshotRepositoryBoundedAndCleanableDuringRuntime() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        ClassIdentity identity = ClassIdentities.of(OrderService.class);
        runtime.publish(method, rule("snap", method, InvokePhase.BEFORE,
                "return mock.returnValue(1)"));
        assertThat(runtime.snapshotRepository().metadataFor(identity)).isNotEmpty();
        runtime.snapshotRepository().clear();
        assertThat(runtime.snapshotRepository().metadataFor(identity)).isEmpty();
        assertThat(runtime.snapshotRepository().size()).isZero();
    }

    // ---- helpers ----

    private MethodSignature signatureOf(Method method) {
        return new MethodSignature(
                method.getDeclaringClass().getName(),
                ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()),
                method.getName(),
                MethodDescriptor.of(method));
    }

    private static MockRule rule(String id, Method method, InvokePhase phase, String script) {
        return MockRule.builder()
                .id(id)
                .name(id)
                .target(MethodSelector.builder()
                        .className(method.getDeclaringClass().getName())
                        .classLoaderId(ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()))
                        .methodName(method.getName())
                        .methodDescriptor(MethodDescriptor.of(method))
                        .build())
                .phase(phase)
                .script(script)
                .priority(100)
                .percentage(100)
                .failOpen(true)
                .enabled(true)
                .build();
    }

    private Class<?> compileAndLoadDuplicateService(String prefix) throws Exception {
        Path dir = Files.createTempDirectory("dup-" + prefix);
        Path sourceDir = dir.resolve("com/example/duplicate");
        Files.createDirectories(sourceDir);
        Path source = sourceDir.resolve("DuplicateService.java");
        Files.writeString(source, """
                package com.example.duplicate;
                public class DuplicateService {
                    public String echo(String value) {
                        return "%s-" + value;
                    }
                }
                """.formatted(prefix));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).isNotNull();
        assertThat(compiler.run(null, null, null, "-d", dir.toString(), source.toString())).isZero();
        URLClassLoader loader = new URLClassLoader(new URL[]{dir.toUri().toURL()}, ClassLoader.getSystemClassLoader());
        return Class.forName("com.example.duplicate.DuplicateService", true, loader);
    }

    private static boolean containsUtf8(byte[] bytes, String token) {
        byte[] needle = token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** A transformer that appends a custom, non-structural class attribute. */
    private static final class AttributeAddingTransformer implements ClassFileTransformer {
        private final String attributeName;
        private final String internalName;

        AttributeAddingTransformer(String attributeName, String internalName) {
            this.attributeName = attributeName;
            this.internalName = internalName;
        }

        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> classBeingRedefined,
                                java.security.ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (name == null || !name.equals(internalName) || classfileBuffer == null) {
                return null;
            }
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, 0);
            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public void visit(int version, int access, String name, String signature,
                                  String superName, String[] interfaces) {
                    super.visit(version, access, name, signature, superName, interfaces);
                    visitAttribute(new MarkerAttribute(attributeName));
                }
            };
            reader.accept(visitor, 0);
            return writer.toByteArray();
        }
    }

    /** Minimal custom attribute: writes only its name with zero-length content. */
    private static final class MarkerAttribute extends Attribute {
        MarkerAttribute(String name) {
            super(name);
        }

        @Override
        protected ByteVector write(ClassWriter cw, byte[] code, int len, int maxStack, int maxLocals) {
            ByteVector vector = new ByteVector();
            vector.putShort(cw.newUTF8(type));
            vector.putInt(0);
            return vector;
        }
    }
}
