package com.example.kairo.agent.server;

/**
 * Size and timing limits enforced by the bytecode diagnostic HTTP routes.
 *
 * <p>All limits are explicit upper bounds: request bodies, binary responses and
 * diagnostic execution time are capped so a single pathological request cannot
 * exhaust agent memory or pin a thread. The defaults are deliberately generous
 * enough for real class files (a single class file is rarely larger than a few
 * MiB) but bounded well below anything that would threaten the target JVM.
 *
 * @param maxRequestBodyBytes       maximum bytes accepted on a POST body
 *                                  (e.g. preview input bytes); larger bodies are
 *                                  rejected with 413 before being fully buffered
 * @param maxBytecodeResponseBytes  maximum bytes a {@code bytecode} response will
 *                                  emit; a snapshot larger than this is rejected
 *                                  with 413 rather than streamed out
 * @param diagnosticTimeoutMillis   hard timeout for preview/capture/diff work
 *                                  dispatched to the diagnostic executor; a
 *                                  timeout is reported as 503
 * @param diagnosticConcurrency     maximum concurrent diagnostic operations; the
 *                                  diagnostic executor is a fixed pool of this
 *                                  size, so excess requests queue rather than run
 *                                  on business or HTTP threads
 */
public record BytecodeApiLimits(
        int maxRequestBodyBytes,
        int maxBytecodeResponseBytes,
        long diagnosticTimeoutMillis,
        int diagnosticConcurrency
) {

    /** Standard limits: 1 MiB request body, 8 MiB response, 10 s timeout, 2 concurrent. */
    public static final BytecodeApiLimits STANDARD = new BytecodeApiLimits(
            1 * 1024 * 1024,
            8 * 1024 * 1024,
            10_000L,
            2
    );

    public BytecodeApiLimits {
        if (maxRequestBodyBytes <= 0) {
            throw new IllegalArgumentException("maxRequestBodyBytes must be > 0: " + maxRequestBodyBytes);
        }
        if (maxBytecodeResponseBytes <= 0) {
            throw new IllegalArgumentException("maxBytecodeResponseBytes must be > 0: " + maxBytecodeResponseBytes);
        }
        if (diagnosticTimeoutMillis <= 0) {
            throw new IllegalArgumentException("diagnosticTimeoutMillis must be > 0: " + diagnosticTimeoutMillis);
        }
        if (diagnosticConcurrency <= 0) {
            throw new IllegalArgumentException("diagnosticConcurrency must be > 0: " + diagnosticConcurrency);
        }
    }
}
