package com.example.kairo.compatmatrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point of {@code verify-compatibility.sh}. Validates an existing
 * {@code compatibility-result.json}: aggregate schema, catalog completeness, a
 * single candidate build id, formal-row status semantics, and evidence/provenance
 * fields. When {@code --doc} and/or {@code --manifest} are supplied (M3-F,
 * section 10.4.6), it additionally cross-checks that the generated
 * {@code docs/compatibility/v1.7.md} was produced from this aggregate and that
 * {@code v1.7-acceptance-manifest.json} has a valid PR -&gt; RC -&gt; RELEASE lifecycle,
 * with complete evidence for every passed gate and no overclaim &mdash; so the aggregate,
 * document and release manifest conclusions cannot diverge.
 *
 * <p>It <strong>does not rerun scenarios</strong>.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 result valid and complete (overall=PASSED, all formal rows PASSED)</li>
 *   <li>1 usage error / file not found</li>
 *   <li>2 build failed (set by the shell runner)</li>
 *   <li>3 verifier unusable</li>
 *   <li>4 result invalid or incomplete (overall=FAILED, or semantic/cross-check violation)</li>
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
        // M3-F cross-checks (section 10.4.6): when a document and/or manifest are supplied,
        // bind the aggregate, document and release-manifest conclusions together. These do
        // not replace the structural validation above; they add divergence rejection.
        if (errors.isEmpty() && opts.doc() != null) {
            errors.addAll(checkDocument(root, opts.doc()));
        }
        if (errors.isEmpty() && opts.manifest() != null) {
            errors.addAll(checkManifest(root, opts.manifest()));
        }
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

    private static List<String> checkDocument(JsonNode root, String docPath) {
        Path p = Path.of(docPath);
        if (!Files.isRegularFile(p)) {
            List<String> e = new ArrayList<>();
            e.add("document not found: " + docPath);
            return e;
        }
        try {
            String doc = Files.readString(p);
            return new CompatibilityDocumentCheck().checkDocument(root, doc);
        } catch (Exception ex) {
            List<String> e = new ArrayList<>();
            e.add("document could not be read (" + ex.getClass().getSimpleName()
                    + "): " + ex.getMessage());
            return e;
        }
    }

    private static List<String> checkManifest(JsonNode root, String manifestPath) {
        Path p = Path.of(manifestPath);
        if (!Files.isRegularFile(p)) {
            List<String> e = new ArrayList<>();
            e.add("manifest not found: " + manifestPath);
            return e;
        }
        try {
            JsonNode manifest = MAPPER.readTree(Files.readString(p));
            return new CompatibilityDocumentCheck().checkManifest(root, manifest);
        } catch (Exception ex) {
            List<String> e = new ArrayList<>();
            e.add("manifest is not parseable JSON (" + ex.getClass().getSimpleName()
                    + "): " + ex.getMessage());
            return e;
        }
    }

    private static void printUsage() {
        System.out.println("""
                Usage: CompatibilityVerifierMain <result.json>
                        [--doc <docs/compatibility/v1.7.md>]
                        [--manifest <v1.7-acceptance-manifest.json>] [--help]

                Validates compatibility-result.json: schema, catalog completeness
                (all C01-C10 present once), a single candidate build id, formal-row
                status semantics (every formal scenario PASSED), summary/count
                consistency, and evidence/provenance fields. Does not rerun scenarios.

                With --doc: verifies the generated document was produced from this
                aggregate (source hash/overall/buildId/catalog version) and does not
                overclaim release readiness.
                With --manifest: verifies the V17-COMPAT PR -> RC -> RELEASE lifecycle,
                complete evidence for passed gates, and no unsupported gate overclaim.

                Exit codes:
                  0  result valid and complete (overall=PASSED)
                  1  usage error / file not found
                  2  build failed (set by the shell runner)
                  3  verifier unusable
                  4  result invalid or incomplete (or cross-check violation)
                  6  malformed JSON (unparseable)
                """);
    }
}
