package com.example.kairo.perf.statecycle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic argument-parsing tests for {@link StateCycleArgumentParser}. No JVM
 * lifecycle; pure string handling. Mirrors the shell-side validation so a missing,
 * zero, negative, non-numeric or below-minimum cycle count, a non-40-hex build id,
 * a missing output/command/jvm-args/mode, a bad mode, and a dirty PR tree all fail
 * fast with a precise message.
 */
class StateCycleArgumentParserTest {

    private static final String BUILD_ID = "0123456789abcdef0123456789abcdef01234567";
    private static final String[] BASE = {
            "--cycles", "500", "--output", "target/v1.7",
            "--build-id", BUILD_ID, "--command", "./run-state-cycle.sh --cycles 500 --output target/v1.7",
            "--jvm-args", "-Xms512m -Xmx512m", "--mode", "pr", "--working-tree-dirty", "false"};

    private static String[] args(String... overrides) {
        java.util.List<String> a = new java.util.ArrayList<>(java.util.Arrays.asList(BASE));
        for (int i = 0; i < overrides.length; i += 2) {
            String flag = overrides[i];
            String val = overrides[i + 1];
            int idx = a.indexOf(flag);
            if (idx < 0) {
                throw new IllegalArgumentException("flag not in base: " + flag);
            }
            a.set(idx + 1, val);
        }
        return a.toArray(new String[0]);
    }

    @Test
    void parsesValidArguments() {
        StateCycleArgumentParser.Options opts = StateCycleArgumentParser.parse(BASE);
        assertThat(opts.help()).isFalse();
        assertThat(opts.cycles()).isEqualTo(500);
        assertThat(opts.output()).isEqualTo("target/v1.7");
        assertThat(opts.buildId()).isEqualTo(BUILD_ID);
        assertThat(opts.mode()).isEqualTo("pr");
        assertThat(opts.workingTreeDirty()).isFalse();
    }

    @Test
    void helpShortCircuitsValidation() {
        StateCycleArgumentParser.Options opts = StateCycleArgumentParser.parse(new String[]{"--help"});
        assertThat(opts.help()).isTrue();
    }

    @Test
    void missingCyclesIsRejected() {
        java.util.List<String> a = new java.util.ArrayList<>(java.util.Arrays.asList(BASE));
        a.remove(0); // "--cycles"
        a.remove(0); // "500"
        assertThatThrownBy(() -> StateCycleArgumentParser.parse(a.toArray(new String[0])))
                .hasMessageContaining("--cycles is required");
    }

    @Test
    void zeroCyclesIsRejected() {
        assertThatThrownBy(() -> StateCycleArgumentParser.parse(args("--cycles", "0")))
                .hasMessageContaining("--cycles must be > 0");
    }

    @Test
    void negativeCyclesIsRejected() {
        assertThatThrownBy(() -> StateCycleArgumentParser.parse(args("--cycles", "-5")))
                .hasMessageContaining("--cycles must be");
    }

    @Test
    void nonNumericCyclesIsRejected() {
        assertThatThrownBy(() -> StateCycleArgumentParser.parse(args("--cycles", "abc")))
                .hasMessageContaining("--cycles must be an integer");
    }

    @Test
    void belowMinimumCyclesIsRejected() {
        assertThatThrownBy(() -> StateCycleArgumentParser.parse(args("--cycles", "5")))
                .hasMessageContaining(">= 6");
    }

    @Test
    void minimumCyclesIsAccepted() {
        StateCycleArgumentParser.Options opts = StateCycleArgumentParser.parse(args("--cycles", "6"));
        assertThat(opts.cycles()).isEqualTo(6);
    }

    @Test
    void missingOutputIsRejected() {
        assertThatThrownBy(() -> StateCycleArgumentParser.parse(
                "--cycles", "6", "--build-id", BUILD_ID, "--command", "c",
                "--jvm-args", "-Xms512m", "--mode", "pr", "--working-tree-dirty", "false"))
                .hasMessageContaining("--output is required");
    }

    @Test
    void nonHexBuildIdIsRejected() {
        assertThatThrownBy(() -> StateCycleArgumentParser.parse(args("--build-id", "not-a-commit")))
                .hasMessageContaining("40-hex");
    }

    @Test
    void shortBuildIdIsRejected() {
        assertThatThrownBy(() -> StateCycleArgumentParser.parse(args("--build-id", "abc123")))
                .hasMessageContaining("40-hex");
    }

    @Test
    void missingModeIsRejected() {
        assertThatThrownBy(() -> StateCycleArgumentParser.parse(
                "--cycles", "6", "--output", "target/v1.7", "--build-id", BUILD_ID,
                "--command", "c", "--jvm-args", "-Xms512m"))
                .hasMessageContaining("--mode is required");
    }

    @Test
    void invalidModeIsRejected() {
        assertThatThrownBy(() -> StateCycleArgumentParser.parse(args("--mode", "rc")))
                .hasMessageContaining("--mode must be 'pr' or 'dev'");
    }

    @Test
    void dirtyPrTreeIsRejected() {
        assertThatThrownBy(() -> StateCycleArgumentParser.parse(
                args("--mode", "pr", "--working-tree-dirty", "true")))
                .hasMessageContaining("dirty");
    }

    @Test
    void dirtyDevTreeIsAccepted() {
        StateCycleArgumentParser.Options opts = StateCycleArgumentParser.parse(
                args("--mode", "dev", "--working-tree-dirty", "true"));
        assertThat(opts.mode()).isEqualTo("dev");
        assertThat(opts.workingTreeDirty()).isTrue();
    }

    @Test
    void unknownArgumentIsRejected() {
        assertThatThrownBy(() -> StateCycleArgumentParser.parse(
                "--cycles", "6", "--bogus", "x"))
                .hasMessageContaining("unknown argument");
    }
}
