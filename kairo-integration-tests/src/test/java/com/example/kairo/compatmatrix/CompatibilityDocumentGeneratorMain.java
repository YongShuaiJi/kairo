package com.example.kairo.compatmatrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point of {@code generate-compatibility-doc.sh}. Reads a single
 * {@code compatibility-result.json} and writes the deterministic
 * {@code docs/compatibility/v1.7.md} via {@link CompatibilityDocumentGenerator}.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 document generated</li>
 *   <li>1 usage / validation error</li>
 *   <li>2 build failed (set by the shell runner)</li>
 *   <li>3 input not found / unreadable</li>
 *   <li>5 write error</li>
 *   <li>6 input is not parseable JSON</li>
 * </ul>
 *
 * <p>The generator never judges whether the matrix passed: it renders whatever the
 * aggregate says. Pass/fail gating is the verifier's job.
 */
public final class CompatibilityDocumentGeneratorMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CompatibilityDocumentGeneratorMain() {
    }

    public static void main(String[] args) {
        System.exit(runInProcess(args));
    }

    static int runInProcess(String[] args) {
        CompatibilityCli.DocOptions opts;
        try {
            opts = CompatibilityCli.parseDoc(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
        if (opts.help()) {
            printUsage();
            return 0;
        }

        Path input = Path.of(opts.input());
        if (!Files.isRegularFile(input)) {
            System.err.println("error: input result not found: " + opts.input());
            return 3;
        }
        JsonNode result;
        try {
            result = MAPPER.readTree(Files.readString(input));
        } catch (Exception e) {
            System.err.println("error: input is not parseable JSON (" + e.getClass().getSimpleName()
                    + "): " + e.getMessage());
            return 6;
        }

        String document;
        try {
            document = CompatibilityDocumentGenerator.generate(result);
        } catch (Exception e) {
            System.err.println("error: document generation failed: " + e.getMessage());
            return 6;
        }

        Path output = Path.of(opts.output());
        try {
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            Files.writeString(output, document);
        } catch (Exception e) {
            System.err.println("error: failed to write document to " + opts.output() + ": " + e.getMessage());
            return 5;
        }
        System.out.println("==> document generated -> " + opts.output());
        return 0;
    }

    private static void printUsage() {
        System.out.println("""
                Usage: CompatibilityDocumentGeneratorMain --input <result.json>
                        --output <docs/compatibility/v1.7.md> [--help]

                Reads the single compatibility-result.json and writes the deterministic
                compatibility support document via CompatibilityDocumentGenerator. The
                document is reproducible from the same input and embeds a SHA-256 of the
                source result; verify-compatibility.sh rejects any divergence.

                Exit codes:
                  0  document generated
                  1  usage / validation error
                  2  build failed (set by the shell runner)
                  3  input not found / unreadable
                  5  write error
                  6  input is not parseable JSON
                """);
    }
}
