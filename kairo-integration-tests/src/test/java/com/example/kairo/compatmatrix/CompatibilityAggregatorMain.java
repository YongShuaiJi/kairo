package com.example.kairo.compatmatrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Entry point of {@code aggregate-compatibility.sh}. Consumes row JSON only, never
 * executes scenarios, and produces the only {@code compatibility-result.json}.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 aggregate valid and all formal rows PASSED (overall=PASSED)</li>
 *   <li>1 usage / validation error</li>
 *   <li>2 build failed (set by the shell runner)</li>
 *   <li>3 aggregator unusable (e.g. input is not a directory)</li>
 *   <li>4 aggregate has failures (overall=FAILED) - fail-closed</li>
 *   <li>5 result-write error</li>
 *   <li>6 schema-validation failure (self-validation of the produced result)</li>
 * </ul>
 */
public final class CompatibilityAggregatorMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CompatibilityAggregatorMain() {
    }

    public static void main(String[] args) {
        System.exit(runInProcess(args, Instant.now()));
    }

    static int runInProcess(String[] args, Instant now) {
        CompatibilityCli.AggregateOptions opts;
        try {
            opts = CompatibilityCli.parseAggregate(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
        if (opts.help()) {
            printUsage();
            return 0;
        }

        Path inputDir = Path.of(opts.input());
        if (!Files.isDirectory(inputDir)) {
            System.err.println("error: --input must be an existing directory of row JSON: " + opts.input());
            return 3;
        }

        List<CompatibilityRowAggregator.ParsedRow> rows = readRows(inputDir);
        CompatibilityRowAggregator.AggregatorMeta meta =
                new CompatibilityRowAggregator.AggregatorMeta(now.toString(), opts.command());
        CompatibilityRowAggregator.AggregationOutcome outcome =
                new CompatibilityRowAggregator(MAPPER).aggregate(rows, meta);

        // Self-validate the produced result. Use the structural validator (not the full
        // verifier gate): an incomplete matrix with missing rows / no build id is an honest
        // FAILED result, not an aggregator malfunction. Structural errors (schema, summary
        // inconsistency, overall/failures mismatch) are aggregator bugs -> exit 6.
        List<String> errors = new CompatibilityResultValidator().validateStructure(outcome.result());
        if (!errors.isEmpty()) {
            System.err.println("error: aggregate failed self-validation: " + String.join("; ", errors));
            // Still best-effort write the result for inspection.
            bestEffortWrite(outcome.result(), opts.output());
            return 6;
        }

        if (!bestEffortWrite(outcome.result(), opts.output())) {
            return 5;
        }

        System.out.println("==> overall=" + outcome.result().get("overall").asText()
                + " rows=" + rows.size()
                + " failures=" + outcome.failureReasons().size()
                + " -> " + opts.output());
        return outcome.overallPassed() ? 0 : 4;
    }

    private static List<CompatibilityRowAggregator.ParsedRow> readRows(Path inputDir) {
        List<CompatibilityRowAggregator.ParsedRow> rows = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(inputDir)) {
            s.filter(p -> p.toString().endsWith(".json")).sorted().forEach(files::add);
        } catch (IOException e) {
            System.err.println("error: could not list input directory " + inputDir + ": " + e.getMessage());
            return rows;
        }
        for (Path f : files) {
            String name = f.getFileName().toString();
            try {
                String content = Files.readString(f);
                JsonNode node = MAPPER.readTree(content);
                rows.add(new CompatibilityRowAggregator.ParsedRow(name, node, null));
            } catch (Exception e) {
                rows.add(new CompatibilityRowAggregator.ParsedRow(name, null,
                        "unparseable JSON (" + e.getClass().getSimpleName() + "): " + e.getMessage()));
            }
        }
        return rows;
    }

    private static boolean bestEffortWrite(ObjectNode result, String output) {
        try {
            Path out = Path.of(output);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), result);
            return true;
        } catch (Exception e) {
            System.err.println("error: failed to write result to " + output + ": " + e.getMessage());
            return false;
        }
    }

    private static void printUsage() {
        System.out.println("""
                Usage: CompatibilityAggregatorMain --input <dir> --output <result.json>
                        --command <text> [--help]

                Consumes every *.json row in <dir>, validates and aggregates them, and
                writes the single compatibility-result.json. Never executes scenarios.

                Fail-closed: a missing/duplicate/unknown/malformed row, a build-id
                mismatch, fake PASSED evidence, or any formal row not PASSED makes the
                aggregate overall=FAILED.

                Exit codes:
                  0  aggregate valid and all formal rows PASSED
                  1  usage / validation error
                  2  build failed (set by the shell runner)
                  3  aggregator unusable
                  4  aggregate has failures (overall=FAILED)
                  5  result-write error
                  6  schema-validation failure
                """);
    }
}
