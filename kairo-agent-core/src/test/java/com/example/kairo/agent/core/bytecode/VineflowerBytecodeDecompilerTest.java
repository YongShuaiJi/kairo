package com.example.kairo.agent.core.bytecode;

import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.DecompilationResult;
import com.example.kairo.api.bytecode.DecompilationStatus;
import example.demo.ExampleTarget;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.FixedValue;
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link VineflowerBytecodeDecompiler}. The decompiler is exercised
 * against the compiled {@link ExampleTarget} fixture so the assertions run on real
 * classfile bytes rather than hand-rolled ones.
 *
 * <p>Per the SPI contract, successful results carry approximate source (the test checks
 * that the target method and class are present, never that the source is byte-exact),
 * and non-successful results never carry source.
 */
class VineflowerBytecodeDecompilerTest {

    private static final String BINARY = "example.demo.ExampleTarget";
    private static final ClassIdentity ID = new ClassIdentity(BINARY, "loader-1");

    private final VineflowerBytecodeDecompiler decompiler = new VineflowerBytecodeDecompiler();

    @Test
    void versionIsResolvedAndInSyncWithVineflowerJar() {
        // The version is resolved from the build-filtered properties resource (which
        // survives shading) and must agree with Vineflower's own jar manifest here, so
        // a vineflower dependency bump cannot silently leave a stale version string.
        assertThat(decompiler.version()).isEqualTo("1.12.0");
        assertThat(decompiler.version()).isEqualTo(ConsoleDecompiler.version());
    }

    @Test
    void decompilesNormalClassWithTargetMethodAndVersionDiagnostic() throws Exception {
        byte[] bytes = readFixture();

        DecompilationResult result = decompiler.decompile(ID, bytes);

        assertThat(result.status()).isEqualTo(DecompilationStatus.SUCCESS);
        assertThat(result.decompilerName()).isEqualTo("vineflower");
        assertThat(result.sourceCode()).isNotBlank();
        // Approximate, not exact: the original class and target method must be present,
        // but the decompiled text is allowed to rename locals or restructure expressions.
        assertThat(result.sourceCode()).contains("ExampleTarget");
        assertThat(result.sourceCode()).contains("calculateScore");
        // The diagnostic must be honest that the source is approximate.
        assertThat(result.diagnostics()).anyMatch(s -> s.contains("approximate"));
        assertThat(result.diagnostics()).anyMatch(s -> s.contains(decompiler.version()));
        assertThat(result.durationMillis()).isNotNegative();
    }

    @Test
    void decompilesByteBuddyEnhancedBytecode() throws Exception {
        // Produce genuinely "enhanced" bytes: ByteBuddy rewrites calculateScore to a
        // fixed return value, mirroring an instrumentation pass. Vineflower must still
        // produce readable source containing the class and method.
        byte[] enhanced = new ByteBuddy()
                .redefine(ExampleTarget.class)
                .method(named("calculateScore"))
                .intercept(FixedValue.value(42))
                .make()
                .getBytes();

        DecompilationResult result = decompiler.decompile(ID, enhanced);

        assertThat(result.status()).isEqualTo(DecompilationStatus.SUCCESS);
        assertThat(result.sourceCode()).contains("ExampleTarget");
        assertThat(result.sourceCode()).contains("calculateScore");
    }

    @Test
    void failsOnGarbageBytesWithoutSource() {
        byte[] garbage = new byte[]{0x00, (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};

        DecompilationResult result = decompiler.decompile(ID, garbage);

        assertThat(result.status()).isEqualTo(DecompilationStatus.FAILED);
        assertThat(result.sourceCode()).isNull();
        assertThat(result.diagnostics()).anyMatch(s -> s.contains("class name"));
    }

    @Test
    void failsOnEmptyBytes() {
        DecompilationResult result = decompiler.decompile(ID, new byte[0]);

        assertThat(result.status()).isEqualTo(DecompilationStatus.FAILED);
        assertThat(result.sourceCode()).isNull();
    }

    @Test
    void failsWhenClassNameDoesNotMatchIdentity() throws Exception {
        byte[] bytes = readFixture();
        // The bytes declare ExampleTarget, but the caller claims a different class.
        ClassIdentity wrong = new ClassIdentity("example.demo.DifferentClass", "loader-1");

        DecompilationResult result = decompiler.decompile(wrong, bytes);

        assertThat(result.status()).isEqualTo(DecompilationStatus.FAILED);
        assertThat(result.sourceCode()).isNull();
        assertThat(result.diagnostics())
                .anyMatch(s -> s.contains("mismatch") && s.contains("DifferentClass"));
    }

    @Test
    void doesNotPolluteSystemOutOrErr() throws Exception {
        // The capturing logger must absorb everything; nothing may reach the console.
        java.io.PrintStream realOut = System.out;
        java.io.PrintStream realErr = System.err;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(out));
        System.setErr(new java.io.PrintStream(err));
        try {
            decompiler.decompile(ID, readFixture());
            decompiler.decompile(ID, new byte[]{1, 2, 3});
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
        assertThat(out.toString()).as("System.out pollution").isBlank();
        assertThat(err.toString()).as("System.err pollution").isBlank();
    }

    private static byte[] readFixture() throws IOException {
        try (InputStream in = ExampleTarget.class.getResourceAsStream("ExampleTarget.class")) {
            assertThat(in).as("ExampleTarget.class resource").isNotNull();
            return in.readAllBytes();
        }
    }
}
