package com.example.kairo.perf.soak;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic unit tests for {@link SoakArgumentParser}. Mirrors the shell-side validation
 * so the harness fails fast with a precise error when invoked directly.
 */
class SoakArgumentParserTest {

    private static final String BUILD_ID = "0123456789abcdef0123456789abcdef01234567";

    private static final String[] FULL_PR = {
            "--duration", "PT2H",
            "--output", "target/v1.7",
            "--build-id", BUILD_ID,
            "--command", "./scripts/v1.7/run-soak.sh --duration PT2H --output target/v1.7",
            "--jvm-args", "-Xms512m -Xmx512m -XX:+AlwaysPreTouch",
            "--mode", "pr",
            "--working-tree-dirty", "false"
    };

    @Test
    void parsesFullPrCommand() {
        SoakArgumentParser.Options opts = SoakArgumentParser.parse(FULL_PR);
        assertThat(opts.help()).isFalse();
        assertThat(opts.duration()).isEqualTo(Duration.ofHours(2));
        assertThat(opts.output()).isEqualTo("target/v1.7");
        assertThat(opts.buildId()).isEqualTo(BUILD_ID);
        assertThat(opts.command()).contains("run-soak.sh");
        assertThat(opts.jvmArgs()).contains("Xmx512m");
        assertThat(opts.mode()).isEqualTo("pr");
        assertThat(opts.workingTreeDirty()).isFalse();
    }

    @Test
    void parsesIso8601DurationsIncludingDays() {
        // RC and RELEASE durations: PT2H (RC) and P7D (RELEASE, §9.6).
        assertThat(SoakArgumentParser.parse(replace(FULL_PR, "PT2H", "PT2H")).duration()).isEqualTo(Duration.ofHours(2));
        assertThat(SoakArgumentParser.parse(replace(FULL_PR, "PT2H", "P7D")).duration()).isEqualTo(Duration.ofDays(7));
        assertThat(SoakArgumentParser.parse(replace(FULL_PR, "PT2H", "PT35M")).duration()).isEqualTo(Duration.ofMinutes(35));
    }

    @Test
    void rejectsMissingDuration() {
        assertThatThrownBy(() -> SoakArgumentParser.parse(remove(FULL_PR, "--duration", "PT2H")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--duration is required");
    }

    @Test
    void rejectsZeroAndNegativeDuration() {
        assertThatThrownBy(() -> SoakArgumentParser.parse(replace(FULL_PR, "PT2H", "PT0S")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly positive");
        assertThatThrownBy(() -> SoakArgumentParser.parse(replace(FULL_PR, "PT2H", "PT-1H")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDurationShorterThanOneRealSummaryWindow() {
        assertThatThrownBy(() -> SoakArgumentParser.parse(replace(FULL_PR, "PT2H", "PT59S")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least PT1M");
    }

    @Test
    void rejectsNonIsoDuration() {
        assertThatThrownBy(() -> SoakArgumentParser.parse(replace(FULL_PR, "PT2H", "2hours")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO-8601");
    }

    @Test
    void rejectsBadBuildId() {
        assertThatThrownBy(() -> SoakArgumentParser.parse(replace(FULL_PR, BUILD_ID, "not-hex")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("40-hex");
    }

    @Test
    void rejectsPrWithDirtyTree() {
        assertThatThrownBy(() -> SoakArgumentParser.parse(replace(FULL_PR, "false", "true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dirty working tree");
    }

    @Test
    void devModeAllowsDirtyTree() {
        String[] dev = replace(replace(FULL_PR, "pr", "dev"), "false", "true");
        SoakArgumentParser.Options opts = SoakArgumentParser.parse(dev);
        assertThat(opts.mode()).isEqualTo("dev");
        assertThat(opts.workingTreeDirty()).isTrue();
    }

    @Test
    void rejectsBadMode() {
        assertThatThrownBy(() -> SoakArgumentParser.parse(replace(FULL_PR, "pr", "rc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--mode");
    }

    @Test
    void helpFlagShortCircuitsValidation() {
        SoakArgumentParser.Options opts = SoakArgumentParser.parse("--help");
        assertThat(opts.help()).isTrue();
    }

    @Test
    void rejectsUnknownArgument() {
        assertThatThrownBy(() -> SoakArgumentParser.parse("--nope", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown argument");
    }

    private static String[] replace(String[] args, String oldVal, String newVal) {
        String[] copy = args.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i].equals(oldVal)) {
                copy[i] = newVal;
                return copy;
            }
        }
        throw new AssertionError("value not found: " + oldVal);
    }

    private static String[] remove(String[] args, String flag, String value) {
        String[] copy = new String[args.length - 2];
        int j = 0;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals(flag) && i + 1 < args.length && args[i + 1].equals(value)) {
                i++; // skip value
                continue;
            }
            copy[j++] = args[i];
        }
        return copy;
    }
}
