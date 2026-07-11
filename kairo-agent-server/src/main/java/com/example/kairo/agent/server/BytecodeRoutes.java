package com.example.kairo.agent.server;

import com.example.kairo.agent.core.LoadedClassRepository;
import com.example.kairo.agent.core.bytecode.BytecodeCaptureService;
import com.example.kairo.agent.core.bytecode.BytecodeHash;
import com.example.kairo.agent.core.bytecode.BytecodeSnapshotKey;
import com.example.kairo.agent.core.bytecode.BytecodeSnapshotRepository;
import com.example.kairo.agent.core.bytecode.TransformationJournal;
import com.example.kairo.agent.core.bytecode.TransformationPreviewService;
import com.example.kairo.agent.core.bytecode.diff.BytecodeDiffService;
import com.example.kairo.api.bytecode.BytecodeDiffResult;
import com.example.kairo.api.bytecode.BytecodeSnapshotKind;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.TransformationDiagnostic;
import com.example.kairo.api.bytecode.TransformationResult;
import com.example.kairo.api.bytecode.TransformationRevision;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Agent local HTTP routes for the V1.1 bytecode-visibility foundation.
 *
 * <p>Exposes five read-only / diagnostic routes under {@code /classes/{classId}/...}:
 * <ul>
 *   <li>{@code GET .../transformations} - per-class revision + bounded journal history (JSON);</li>
 *   <li>{@code GET .../bytecode?kind=&revision=} - raw snapshot bytes
 *       ({@code application/octet-stream});</li>
 *   <li>{@code POST .../preview} - offline planned-bytes preview of supplied input bytes (JSON);</li>
 *   <li>{@code POST .../capture} - re-read the bytes actually running in the JVM (JSON);</li>
 *   <li>{@code GET .../diff?from=&to=&format=} - structured normalized bytecode diff (JSON/text).</li>
 * </ul>
 *
 * <p>The {@code classId} is the existing {@link LoadedClassRepository#classId} form
 * (base64url of {@code classLoaderId|binaryClassName}), so it unambiguously locates a
 * class by <em>both</em> {@code binaryClassName} and {@code classLoaderId}; a bare class
 * name is never accepted. Malformed classIds are rejected with 400; a class required for
 * a live operation (capture) that is not currently loaded is 404; a missing snapshot is 404.
 *
 * <p>Browser-direct access is not enforced here (the Platform proxy is a later slice);
 * these routes reuse the Agent's existing token authentication via the
 * {@code AgentHttpServer} wrapper. Slow preview/capture/diff work is dispatched to a
 * bounded {@link BytecodeDiagnosticExecutor} so it never runs on a business thread.
 *
 * <p>This handler never throws: every outcome - including unexpected internal errors -
 * is written as a structured JSON error body without a stack trace, so the shared
 * {@code authenticated()} wrapper's catch-all (which would leak the exception class) is
 * never triggered for bytecode routes.
 */
final class BytecodeRoutes implements AutoCloseable {

    private final LoadedClassRepository loadedClassRepository;
    private final BytecodeSnapshotRepository snapshotRepository;
    private final TransformationJournal journal;
    private final TransformationPreviewService previewService;
    private final BytecodeCaptureService captureService;
    private final BytecodeDiffService diffService;
    private final ObjectMapper objectMapper;
    private final BytecodeApiLimits limits;
    private final BytecodeDiagnosticExecutor diagnosticExecutor;

    BytecodeRoutes(LoadedClassRepository loadedClassRepository,
                   BytecodeSnapshotRepository snapshotRepository,
                   TransformationJournal journal,
                   TransformationPreviewService previewService,
                   BytecodeCaptureService captureService,
                   BytecodeDiffService diffService,
                   ObjectMapper objectMapper,
                   BytecodeApiLimits limits) {
        this.loadedClassRepository = loadedClassRepository;
        this.snapshotRepository = snapshotRepository;
        this.journal = journal;
        this.previewService = previewService;
        this.captureService = captureService;
        this.diffService = diffService;
        this.objectMapper = objectMapper;
        this.limits = limits;
        this.diagnosticExecutor = new BytecodeDiagnosticExecutor(
                limits.diagnosticTimeoutMillis(), limits.diagnosticConcurrency());
    }

    /**
     * Attempt to handle the request as a bytecode route.
     *
     * @return {@code true} if the method+path matched a bytecode route (a response was
     *         written, including error responses); {@code false} so the caller can try
     *         other routes.
     */
    boolean handle(HttpExchange exchange, String method, String path) {
        String route = match(method, path);
        if (route == null) {
            return false;
        }
        try {
            switch (route) {
                case "transformations" -> handleTransformations(exchange, path);
                case "bytecode" -> handleBytecode(exchange, path, "HEAD".equals(method));
                case "preview" -> handlePreview(exchange, path);
                case "capture" -> handleCapture(exchange, path);
                case "diff" -> handleDiff(exchange, path);
                default -> writeError(exchange, 404, "not_found", "unknown route");
            }
        } catch (BadRequestException e) {
            writeError(exchange, 400, "bad_request", e.getMessage());
        } catch (NotFoundException e) {
            writeError(exchange, 404, "not_found", e.getMessage());
        } catch (PayloadTooLargeException e) {
            writeError(exchange, 413, "payload_too_large", e.getMessage());
        } catch (BytecodeDiagnosticExecutor.DiagnosticTimeoutException e) {
            writeError(exchange, 503, "diagnostic_timeout", e.getMessage());
        } catch (BytecodeDiagnosticExecutor.DiagnosticBusyException e) {
            writeError(exchange, 503, "diagnostic_busy", e.getMessage());
        } catch (BytecodeDiagnosticExecutor.DiagnosticFailedException e) {
            writeError(exchange, 500, "diagnostic_failed",
                    "diagnostic operation failed; see agent logs");
        } catch (Exception e) {
            writeError(exchange, 500, "internal_error",
                    "internal error; see agent logs");
        }
        return true;
    }

    @Override
    public void close() {
        diagnosticExecutor.close();
    }

    // ---- routes ----

    private void handleTransformations(HttpExchange exchange, String path) throws IOException {
        String classId = segment(path, "/classes/", "/transformations");
        ClassIdentity identity = identity(classId);
        TransformationRevision currentRevision = journal.currentRevision(identity);
        List<TransformationResult> history = journal.history(identity);
        writeJson(exchange, 200, new TransformationsResponse(
                identity, currentRevision, history.size(), history));
    }

    private void handleBytecode(HttpExchange exchange, String path, boolean head) throws IOException {
        String classId = segment(path, "/classes/", "/bytecode");
        ClassIdentity identity = identity(classId);
        Map<String, String> query = query(exchange.getRequestURI());
        BytecodeSnapshotKind kind = parseKind(query.get("kind"));
        TransformationRevision revision = TransformationRevision.of(parseRevision(query.get("revision")));
        BytecodeSnapshotKey key = new BytecodeSnapshotKey(identity, revision, kind);
        Optional<byte[]> bytes = snapshotRepository.bytes(key);
        if (bytes.isEmpty()) {
            throw new NotFoundException("snapshot not found: " + kind + "@" + revision.value()
                    + " for " + identity.binaryClassName());
        }
        byte[] payload = bytes.get();
        if (payload.length > limits.maxBytecodeResponseBytes()) {
            throw new PayloadTooLargeException("snapshot " + payload.length
                    + "B exceeds response limit " + limits.maxBytecodeResponseBytes() + "B");
        }
        writeBytecode(exchange, payload, kind, revision, BytecodeHash.sha256Hex(payload), head);
    }

    private void handlePreview(HttpExchange exchange, String path) throws IOException {
        String classId = segment(path, "/classes/", "/preview");
        ClassIdentity identity = identity(classId);
        byte[] inputBytes = readBoundedBody(exchange);
        if (inputBytes.length == 0) {
            throw new BadRequestException("preview requires input bytes in the request body"
                    + " (application/octet-stream); fetch them via GET .../bytecode?kind=APPLIED");
        }
        TransformationPreviewService.PreviewResult result = diagnosticExecutor.submitAndAwait(
                () -> previewService.preview(identity, inputBytes));
        Integer plannedSize = result.plannedBytes() == null ? null : result.plannedBytes().length;
        writeJson(exchange, 200, new PreviewResponse(
                result.classIdentity(),
                result.revision(),
                result.inputHash(),
                result.plannedHash(),
                plannedSize,
                result.targetMethodCount(),
                result.adviceTypes(),
                result.diagnostics(),
                result.changed()));
    }

    private void handleCapture(HttpExchange exchange, String path) throws IOException {
        String classId = segment(path, "/classes/", "/capture");
        Class<?> clazz = resolveClass(classId);
        BytecodeCaptureService.CaptureResult result = diagnosticExecutor.submitAndAwait(
                () -> captureService.capture(clazz));
        Integer size = result.appliedBytes() == null ? null : result.appliedBytes().length;
        writeJson(exchange, 200, new CaptureResponse(
                result.classIdentity(),
                result.revision(),
                result.appliedHash(),
                size,
                result.diagnostics(),
                result.capturedAtMillis(),
                result.captured()));
    }

    private void handleDiff(HttpExchange exchange, String path) throws IOException {
        String classId = segment(path, "/classes/", "/diff");
        ClassIdentity identity = identity(classId);
        Map<String, String> query = query(exchange.getRequestURI());
        SnapshotSelector from = parseSelector(query.get("from"));
        SnapshotSelector to = parseSelector(query.get("to"));
        String format = query.getOrDefault("format", "json");
        byte[] fromBytes = snapshotBytes(identity, from, "from");
        byte[] toBytes = snapshotBytes(identity, to, "to");
        BytecodeDiffResult result = diagnosticExecutor.submitAndAwait(
                () -> diffService.diff(identity,
                        fromBytes, from.revision(), from.kind(),
                        toBytes, to.revision(), to.kind()));
        if ("text".equalsIgnoreCase(format)) {
            writeText(exchange, 200, renderTextDiff(result));
        } else if ("json".equalsIgnoreCase(format)) {
            writeJson(exchange, 200, result);
        } else {
            throw new BadRequestException("unsupported format: " + format + " (expected json or text)");
        }
    }

    private byte[] snapshotBytes(ClassIdentity identity, SnapshotSelector selector, String side) {
        BytecodeSnapshotKey key = new BytecodeSnapshotKey(identity, selector.revision(), selector.kind());
        return snapshotRepository.bytes(key)
                .orElseThrow(() -> new NotFoundException(side + " snapshot not found: "
                        + selector.kind() + "@" + selector.revision().value()
                        + " for " + identity.binaryClassName()));
    }

    // ---- response shapes ----

    private record TransformationsResponse(
            ClassIdentity classIdentity,
            TransformationRevision currentRevision,
            int count,
            List<TransformationResult> history
    ) {
    }

    private record PreviewResponse(
            ClassIdentity classIdentity,
            TransformationRevision revision,
            String inputHash,
            String plannedHash,
            Integer plannedSizeBytes,
            int targetMethodCount,
            Set<String> adviceTypes,
            List<TransformationDiagnostic> diagnostics,
            boolean changed
    ) {
    }

    private record CaptureResponse(
            ClassIdentity classIdentity,
            TransformationRevision revision,
            String appliedHash,
            Integer sizeBytes,
            List<TransformationDiagnostic> diagnostics,
            long capturedAtMillis,
            boolean captured
    ) {
    }

    // ---- parsing helpers (pure, unit-tested) ----

    private String match(String method, String path) {
        if (path == null || !path.startsWith("/classes/")) {
            return null;
        }
        if ("GET".equals(method) && path.endsWith("/transformations")) {
            return "transformations";
        }
        if (("GET".equals(method) || "HEAD".equals(method)) && path.endsWith("/bytecode")) {
            return "bytecode";
        }
        if ("POST".equals(method) && path.endsWith("/preview")) {
            return "preview";
        }
        if ("POST".equals(method) && path.endsWith("/capture")) {
            return "capture";
        }
        if ("GET".equals(method) && path.endsWith("/diff")) {
            return "diff";
        }
        return null;
    }

    private ClassIdentity identity(String classId) {
        try {
            return loadedClassRepository.toClassIdentity(classId);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid classId: " + classId);
        }
    }

    private Class<?> resolveClass(String classId) {
        Optional<Class<?>> found;
        try {
            found = loadedClassRepository.findClass(classId);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid classId: " + classId);
        }
        return found.orElseThrow(() -> new NotFoundException("class not loaded: " + classId));
    }

    static BytecodeSnapshotKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("kind is required (INPUT, PLANNED or APPLIED)");
        }
        try {
            return BytecodeSnapshotKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid kind: " + raw + " (expected INPUT, PLANNED or APPLIED)");
        }
    }

    static long parseRevision(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("revision is required (non-negative integer)");
        }
        long value;
        try {
            value = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("invalid revision: " + raw + " (expected non-negative integer)");
        }
        if (value < 0) {
            throw new BadRequestException("revision must be >= 0: " + value);
        }
        return value;
    }

    static SnapshotSelector parseSelector(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("snapshot selector is required (KIND@revision, e.g. INPUT@1)");
        }
        int at = raw.lastIndexOf('@');
        if (at <= 0 || at == raw.length() - 1) {
            throw new BadRequestException("invalid snapshot selector: " + raw
                    + " (expected KIND@revision, e.g. INPUT@1)");
        }
        BytecodeSnapshotKind kind = parseKind(raw.substring(0, at));
        long revision = parseRevision(raw.substring(at + 1));
        return new SnapshotSelector(kind, TransformationRevision.of(revision));
    }

    record SnapshotSelector(BytecodeSnapshotKind kind, TransformationRevision revision) {
    }

    // ---- IO helpers ----

    private byte[] readBoundedBody(HttpExchange exchange) throws IOException {
        int max = limits.maxRequestBodyBytes();
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > max) {
                    throw new PayloadTooLargeException("request body exceeds limit " + max + "B");
                }
            }
            return buffer.toByteArray();
        }
    }

    private void writeBytecode(HttpExchange exchange, byte[] payload,
                               BytecodeSnapshotKind kind, TransformationRevision revision,
                               String hash, boolean head) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Kairo-Protocol", AgentHttpServer.PROTOCOL_VERSION);
        exchange.getResponseHeaders().set("X-Kairo-Kind", kind.name());
        exchange.getResponseHeaders().set("X-Kairo-Revision", String.valueOf(revision.value()));
        exchange.getResponseHeaders().set("X-Kairo-Hash", hash);
        exchange.getResponseHeaders().set("X-Kairo-Size", String.valueOf(payload.length));
        if (head) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(body);
        if (status < 400) {
            ensureResponseWithinLimit(json.length);
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Kairo-Protocol", AgentHttpServer.PROTOCOL_VERSION);
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, json.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(json);
        }
    }

    /**
     * Best-effort structured error response. If the exchange is already broken
     * (e.g. the body was partially written before the failure), the IOException is
     * swallowed so {@code handle} never throws - the {@code authenticated()} wrapper's
     * catch-all (which would leak the exception class) is never triggered.
     */
    private void writeError(HttpExchange exchange, int status, String error, String message) {
        try {
            writeJson(exchange, status, errorBody(error, message));
        } catch (IOException ignored) {
            // transport already broken; the wrapper closes the exchange
        }
    }

    private void writeText(HttpExchange exchange, int status, String body) throws IOException {
        byte[] text = body.getBytes(StandardCharsets.UTF_8);
        ensureResponseWithinLimit(text.length);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Kairo-Protocol", AgentHttpServer.PROTOCOL_VERSION);
        exchange.sendResponseHeaders(status, text.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(text);
        }
    }

    private void ensureResponseWithinLimit(int size) {
        if (size > limits.maxBytecodeResponseBytes()) {
            throw new PayloadTooLargeException("response " + size
                    + "B exceeds response limit " + limits.maxBytecodeResponseBytes() + "B");
        }
    }

    private String renderTextDiff(BytecodeDiffResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("# bytecode diff for ").append(result.classIdentity().binaryClassName()).append('\n');
        sb.append("from: ").append(result.fromKind()).append('@').append(result.fromRevision().value())
                .append(" (").append(result.fromHash() == null ? "n/a" : result.fromHash()).append(")\n");
        sb.append("to:   ").append(result.toKind()).append('@').append(result.toRevision().value())
                .append(" (").append(result.toHash() == null ? "n/a" : result.toHash()).append(")\n");
        sb.append("identical: ").append(result.identical())
                .append(" (normalized: ").append(result.normalized()).append(")\n");
        sb.append("summary: ").append(result.summary() == null ? "" : result.summary()).append('\n');
        if (!result.structuralDiffs().isEmpty()) {
            sb.append("\n## structural\n");
            for (String d : result.structuralDiffs()) {
                sb.append("- ").append(d).append('\n');
            }
        }
        if (!result.methodDiffs().isEmpty()) {
            sb.append("\n## methods\n");
            for (BytecodeDiffResult.MethodDiff m : result.methodDiffs()) {
                sb.append("### ").append(m.methodName()).append(m.methodDescriptor())
                        .append(" [").append(m.changeType()).append("]\n");
                for (String attr : m.attributeDiffs()) {
                    sb.append("  attr: ").append(attr).append('\n');
                }
                for (String instr : m.instructionDiffs()) {
                    sb.append("  ").append(instr).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private Map<String, String> errorBody(String error, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        return body;
    }

    private Map<String, String> query(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String part : raw.split("&")) {
            int separator = part.indexOf('=');
            String key = separator < 0 ? part : part.substring(0, separator);
            String value = separator < 0 ? "" : part.substring(separator + 1);
            values.put(urlDecode(key), urlDecode(value));
        }
        return values;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String segment(String path, String prefix, String suffix) {
        return path.substring(prefix.length(), path.length() - suffix.length());
    }

    // ---- control-flow exceptions ----

    private static final class BadRequestException extends RuntimeException {
        BadRequestException(String message) {
            super(message);
        }
    }

    private static final class NotFoundException extends RuntimeException {
        NotFoundException(String message) {
            super(message);
        }
    }

    private static final class PayloadTooLargeException extends RuntimeException {
        PayloadTooLargeException(String message) {
            super(message);
        }
    }
}
