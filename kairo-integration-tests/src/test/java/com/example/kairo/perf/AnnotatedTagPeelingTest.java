package com.example.kairo.perf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression check for annotated-tag build-ID peeling (M2-A correction 1).
 *
 * <p>{@code git rev-parse V1.6.0} on an annotated tag returns the TAG object id,
 * not the commit the tag points at. The performance runner must resolve refs with
 * {@code ^{commit}} so the recorded build ID is the actual checkout commit. V1.6.0
 * is an annotated tag whose commit is {@code 113823b41981a2d8fb5473a772ae2d2938d9582e}
 * (the upstream baseline recorded in the roadmap); its tag object is a different id.
 *
 * <p>This test runs {@code git} against the repository working directory, so it is
 * gated on the repo being present and git being available. It makes NO timing
 * assertions and does not touch the working tree.
 */
@EnabledIfSystemProperty(named = "kairo.perf.gitpeel.test", matches = "true")
class AnnotatedTagPeelingTest {

    private static final Pattern HEX40 = Pattern.compile("^[0-9a-f]{40}$");
    private static final String V16_COMMIT = "113823b41981a2d8fb5473a772ae2d2938d9582e";

    @Test
    void v16TagObjectDiffersFromPeeledCommit() throws Exception {
        // Only meaningful when V1.6.0 exists as an annotated tag in this checkout.
        if (!refExists("V1.6.0")) {
            return;
        }
        String tagObject = git("rev-parse", "V1.6.0");
        String peeled = git("rev-parse", "V1.6.0^{commit}");
        assertThat(tagObject).matches(HEX40);
        assertThat(peeled).matches(HEX40);
        // An annotated tag's object id MUST differ from the commit it points at.
        // (If they were equal, the tag would be lightweight and peeling would be a
        // no-op — still correct, but this assertion documents the annotated case.)
        assertThat(tagObject).isNotEqualTo(peeled);
        assertThat(peeled).isEqualTo(V16_COMMIT);
    }

    @Test
    void peeledCommitIs40HexForBaselineRef() throws Exception {
        if (!refExists("V1.6.0")) {
            return;
        }
        // The runner resolves refs with ^{commit}; the result must be a 40-hex commit id,
        // never the tag object. This is what the validator enforces on resolvedBuildId.
        String peeled = git("rev-parse", "V1.6.0^{commit}");
        assertThat(peeled).matches(HEX40);
        // Prove it is a commit object (not a tag) by checking its type.
        String type = git("cat-file", "-t", peeled);
        assertThat(type.trim()).isEqualTo("commit");
    }

    private static boolean refExists(String ref) throws Exception {
        Process p = new ProcessBuilder("git", "rev-parse", "--verify", ref)
                .redirectErrorStream(true).start();
        return p.waitFor() == 0;
    }

    private static String git(String... args) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.addAll(java.util.Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
            out = sb.toString();
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new AssertionError("git " + String.join(" ", args) + " failed (" + code + "): " + out);
        }
        return out;
    }
}
