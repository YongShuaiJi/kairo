package com.example.kairo.agent.core.bytecode.diff;

import com.example.kairo.api.bytecode.BytecodeDiffResult;
import com.example.kairo.api.bytecode.BytecodeSnapshotKind;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.TransformationRevision;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassWriter;
import org.junit.jupiter.api.Test;

import example.demo.ExampleTarget;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class BytecodeDiffServiceTest {

    private static final ClassIdentity ID = new ClassIdentity("example/demo/ExampleTarget", "loader-1");
    private static final TransformationRevision R1 = TransformationRevision.of(1);
    private static final TransformationRevision R2 = TransformationRevision.of(2);

    private final BytecodeDiffService service = new BytecodeDiffService();

    @Test
    void identicalBytesAreIdentical() throws Exception {
        byte[] a = readFixture();
        BytecodeDiffResult result = diff(a, a);
        assertThat(result.identical()).isTrue();
        assertThat(result.normalized()).isTrue();
        assertThat(result.methodDiffs()).isEmpty();
        assertThat(result.structuralDiffs()).isEmpty();
    }

    @Test
    void constantPoolReorderingDoesNotCauseFalseDiff() throws Exception {
        byte[] original = readFixture();
        // Round-trip through a fresh ClassWriter (no reader) so ASM rebuilds the
        // constant pool in its own order. The bytes are semantically identical.
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(original).accept(writer, 0);
        byte[] roundTripped = writer.toByteArray();

        // The raw bytes (and therefore the hashes) must differ for the test to
        // mean anything; if a future ASM produces byte-identical output the
        // identical-flag assertion below still proves the property.
        if (Arrays.equals(original, roundTripped)) {
            // Force a guaranteed-different but semantically-equal variant by
            // recomputing frames, which the normalizer ignores.
            ClassWriter recomputed = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            new ClassReader(original).accept(recomputed, 0);
            roundTripped = recomputed.toByteArray();
        }
        assertThat(roundTripped).isNotEqualTo(original);

        BytecodeDiffResult result = diff(original, roundTripped);
        assertThat(result.identical())
                .as("constant-pool/frame noise must not produce a false diff")
                .isTrue();
        assertThat(result.fromHash()).isNotEqualTo(result.toHash());
        assertThat(result.methodDiffs()).isEmpty();
        assertThat(result.structuralDiffs()).isEmpty();
    }

    @Test
    void recognizesRealInstructionDifference() throws Exception {
        byte[] original = readFixture();
        // Patch the fixture's method body: replace IADD (0x60) marker by flipping
        // the arithmetic via a synthetic rewrite through a ClassWriter that swaps
        // IMUL for IADD in calculateScore. We use a visitor to rewrite the body.
        byte[] modified = rewriteToIadd(original);

        BytecodeDiffResult result = diff(original, modified);
        assertThat(result.identical()).isFalse();
        assertThat(result.methodDiffs()).hasSize(1);
        BytecodeDiffResult.MethodDiff md = result.methodDiffs().get(0);
        assertThat(md.methodName()).isEqualTo("calculateScore");
        assertThat(md.changeType()).isEqualTo(BytecodeDiffResult.ChangeType.MODIFIED);
        assertThat(md.instructionDiffs()).anyMatch(line -> line.startsWith("-") && line.contains("IMUL"));
        assertThat(md.instructionDiffs()).anyMatch(line -> line.startsWith("+") && line.contains("IADD"));
    }

    @Test
    void nullSideIsReportedAsUnnormalized() {
        BytecodeDiffResult result = service.diff(ID,
                null, R1, BytecodeSnapshotKind.INPUT,
                new byte[]{1}, R2, BytecodeSnapshotKind.PLANNED);
        assertThat(result.normalized()).isFalse();
        assertThat(result.identical()).isFalse();
        assertThat(result.summary()).contains("null");
    }

    @Test
    void garbageBytesAreReportedAsUnnormalized() {
        BytecodeDiffResult result = service.diff(ID,
                new byte[]{1, 2, 3}, R1, BytecodeSnapshotKind.INPUT,
                new byte[]{4, 5, 6}, R2, BytecodeSnapshotKind.PLANNED);
        assertThat(result.normalized()).isFalse();
        assertThat(result.identical()).isFalse();
        assertThat(result.structuralDiffs()).anyMatch(s -> s.contains("normalization failed"));
    }

    private BytecodeDiffResult diff(byte[] from, byte[] to) {
        return service.diff(ID, from, R1, BytecodeSnapshotKind.INPUT, to, R2, BytecodeSnapshotKind.PLANNED);
    }

    private static byte[] readFixture() throws IOException {
        try (InputStream in = ExampleTarget.class.getResourceAsStream("ExampleTarget.class")) {
            assertThat(in).as("ExampleTarget.class resource").isNotNull();
            return in.readAllBytes();
        }
    }

    private static byte[] rewriteToIadd(byte[] original) {
        ClassWriter writer = new ClassWriter(0);
        ClassReader reader = new ClassReader(original);
        reader.accept(new net.bytebuddy.jar.asm.ClassVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, writer) {
            @Override
            public net.bytebuddy.jar.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                                                                   String signature, String[] exceptions) {
                net.bytebuddy.jar.asm.MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null || !"calculateScore".equals(name)) {
                    return mv;
                }
                return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, mv) {
                    @Override
                    public void visitInsn(int opcode) {
                        // IMUL (0x68) -> IADD (0x60): semantically different instruction
                        super.visitInsn(opcode == net.bytebuddy.jar.asm.Opcodes.IMUL
                                ? net.bytebuddy.jar.asm.Opcodes.IADD : opcode);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }
}
