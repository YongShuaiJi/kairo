package com.example.kairo.compatmatrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Entry point of {@code verify-compatibility.sh}. Validates an existing
 * {@code compatibility-result.json}: aggregate schema, catalog completeness, a
 * single candidate build id, formal-row status semantics, and evidence/provenance
 * fields. It <strong>does not rerun scenarios</strong> and does not read
 * {@code docs/compatibility/v1.7.md} or the release manifest (that cross-check is
 * M3-F, section 10.4.6).
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 result valid and complete (overall=PASSED, all formal rows PASSED)</li>
 *   <li>1 usage error / file not found</li>
 *   <li>2 build failed (set by the shell runner)</li>
 *   <li>3 verifier unusable</li>
 *   <li>4 result invalid or incomplete (overall=FAILED, or semantic violation)</li>
 *   <li>6 malformed JSON (unparseable)</li>
 * </ul>
 */
public final class CompatibilityVerifierMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CompatibilityVerifierMain() {
    }

    public static void main(String[] args) {
        System.exit(runInProcess(args));
    }

    static int runInProcess(String[] args) {
        CompatibilityCli.VerifyOptions opts;
        try {
            opts = CompatibilityCli.parseVerify(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
        if (opts.help()) {
            printUsage();
            return 0;
        }

        Path file = Path.of(opts.resultFile());
        if (!Files.isRegularFile(file)) {
            System.err.println("error: result file not found: " + opts.resultFile());
            return 1;
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(file));
        } catch (Exception e) {
            System.err.println("error: result is not parseable JSON (" + e.getClass().getSimpleName()
                    + "): " + e.getMessage());
            return 6;
        }

        List<String> errors = new CompatibilityResultValidator().validate(root);
        if (!errors.isEmpty()) {
            System.err.println("error: result failed validation:");
            for (String e : errors) {
                System.err.println("  - " + e);
            }
            return 4;
        }

        // Structurally valid. The verifier is the final gate: exit 0 only when the
        // matrix actually passes (overall=PASSED). A valid but FAILED result (e.g. the
        // M3-A NOT_RUN state) is an incomplete matrix -> exit 4.
        String overall = root.path("overall").isTextual() ? root.get("overall").asText() : "";
        if (!"PASSED".equals(overall)) {
            System.err.println("error: result is structurally valid but overall=" + overall
                    + " (expected PASSED: formal rows have not all passed)");
            return 4;
        }

        System.out.println("==> result valid: overall=PASSED");
        return 0;
    }

    private static void printUsage() {
        System.out.println("""
                Usage: CompatibilityVerifierMain <result.json> [--help]

                Validates compatibility-result.json: schema, catalog completeness
                (all C01-C10 present once), a single candidate build id, formal-row
                status semantics (every formal scenario PASSED), summary/count
                consistency, and evidence/provenance fields. Does not rerun scenarios.

                Exit codes:
                  0  result valid and complete (overall=PASSED)
                  1  usage error / file not found
                  2  build failed (set by the shell runner)
                  3  verifier unusable
                  4  result invalid or incomplete
                  6  malformed JSON (unparseable)
                """);
    }
}
