package com.example.kairo.agent.server;

import com.example.bytecode.SampleService;
import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.MockRule;
import com.example.kairo.api.bytecode.BytecodeSnapshotKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for the Agent local bytecode HTTP routes against a real
 * {@link AgentRuntime} and {@link AgentHttpServer} on a loopback port. Exercises
 * every required case: success across all five routes, illegal classId (400),
 * cross-ClassLoader isolation, missing snapshot (404), size limits (413) and
 * token authentication (401/200).
 */
class BytecodeHttpIntegrationTest {

    private static final String TOKEN = "integration-token";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private Instrumentation instrumentation;
    private AgentRuntime runtime;
    private AgentHttpServer server;
    private String base;

    @BeforeEach
    void setUp() {
        instrumentation = ByteBuddyAgent.install();
        runtime = new AgentRuntime(instrumentation);
        runtime.start();
        server = new AgentHttpServer(runtime, "127.0.0.1", 0, TOKEN);
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void bytecodeRoutesRequireToken() throws Exception {
        String classId = classId(SampleService.class);
        // No token -> 401 on every bytecode route.
        assertThat(getNoAuth("/v1/classes/" + classId + "/transformations").status()).isEqualTo(401);
        assertThat(postNoAuth("/v1/classes/" + classId + "/capture").status()).isEqualTo(401);

        // Valid token -> 200 (empty history is a valid answer before any transform).
        Resp transformations = get("/v1/classes/" + classId + "/transformations");
        assertThat(transformations.status()).isEqualTo(200);
        assertThat(transformations.json().path("history").isArray()).isTrue();
        assertThat(transformations.json().path("currentRevision").path("value").asLong()).isZero();
    }

    @Test
    void illegalClassIdIsRejectedWith400() throws Exception {
        // "YWJj" is valid base64url but decodes to "abc" (no classLoaderId|className
        // separator), so it cannot locate a class on any route.
        assertThat(get("/v1/classes/YWJj/transformations").status()).isEqualTo(400);
        assertThat(post("/v1/classes/YWJj/capture", new byte[0]).status()).isEqualTo(400);
        assertThat(get("/v1/classes/YWJj/bytecode?kind=INPUT&revision=0").status()).isEqualTo(400);
    }

    @Test
    void transformationsAndBytecodeAfterRealTransform() throws Exception {
        Method compute = SampleService.class.getMethod("compute", int.class);
        runtime.publish(compute, rule("t", compute, InvokePhase.BEFORE, "return mock.returnValue(999)"));
        assertThat(new SampleService().compute(1)).isEqualTo(999);

        String classId = classId(SampleService.class);
        Resp transformations = get("/v1/classes/" + classId + "/transformations");
        assertThat(transformations.status()).isEqualTo(200);
        JsonNode body = transformations.json();
        assertThat(body.path("currentRevision").path("value").asLong()).isEqualTo(1L);
        assertThat(body.path("classIdentity").path("binaryClassName").asText())
                .isEqualTo(SampleService.class.getName());
        assertThat(body.path("classIdentity").path("classLoaderId").asText()).isNotBlank();
        JsonNode history = body.path("history");
        assertThat(history.size()).isGreaterThan(0);
        assertThat(history.findValuesAsText("status")).contains("STARTED", "SUCCEEDED");

        // Raw INPUT bytes of the transform, served as octet-stream.
        Resp bytecode = get("/v1/classes/" + classId + "/bytecode?kind=INPUT&revision=1");
        assertThat(bytecode.status()).isEqualTo(200);
        assertThat(bytecode.header("Content-Type")).startsWith("application/octet-stream");
        assertThat(bytecode.header("X-Kairo-Kind")).isEqualTo("INPUT");
        assertThat(bytecode.header("X-Kairo-Revision")).isEqualTo("1");
        assertThat(bytecode.header("X-Kairo-Hash")).isNotBlank();
        assertThat(bytecode.body().length).isGreaterThan(0);
        assertThat(bytecode.body().length).isEqualTo(Integer.parseInt(bytecode.header("X-Kairo-Size")));

        // HEAD returns the same headers without a body.
        Resp head = send(headRequest("/v1/classes/" + classId + "/bytecode?kind=INPUT&revision=1"));
        assertThat(head.status()).isEqualTo(200);
        assertThat(head.header("X-Kairo-Hash")).isEqualTo(bytecode.header("X-Kairo-Hash"));
        assertThat(head.body()).isEmpty();
    }

    @Test
    void captureReturnsAppliedBytesAndStoresSnapshot() throws Exception {
        Method compute = SampleService.class.getMethod("compute", int.class);
        runtime.publish(compute, rule("cap", compute, InvokePhase.BEFORE, "return mock.returnValue(7)"));

        String classId = classId(SampleService.class);
        Resp capture = post("/v1/classes/" + classId + "/capture", new byte[0]);
        assertThat(capture.status()).isEqualTo(200);
        JsonNode body = capture.json();
        assertThat(body.path("captured").asBoolean()).isTrue();
        assertThat(body.path("appliedHash").asText()).isNotBlank();
        assertThat(body.path("sizeBytes").asInt()).isGreaterThan(0);
        assertThat(body.path("revision").path("value").asLong()).isEqualTo(1L);

        // The default Vineflower decompiler is on the agent classpath, so capture
        // returns approximate source for the actual running bytes.
        JsonNode decomp = body.path("decompilation");
        assertThat(decomp.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(decomp.path("decompilerName").asText()).isEqualTo("vineflower");
        assertThat(decomp.path("sourceCode").asText()).contains("SampleService");
        assertThat(decomp.path("sourceCode").asText()).contains("compute");

        // The APPLIED snapshot is now fetchable.
        Resp applied = get("/v1/classes/" + classId + "/bytecode?kind=APPLIED&revision=1");
        assertThat(applied.status()).isEqualTo(200);
        assertThat(applied.header("X-Kairo-Hash")).isEqualTo(body.path("appliedHash").asText());
    }

    @Test
    void captureReturns404ForUnknownClass() throws Exception {
        // A classId whose format is valid but whose class is not loaded in the JVM.
        String unknownClassId = encodeClassId("definitely.not.loaded.Clazz", "loader-that-does-not-exist");
        Resp capture = post("/v1/classes/" + unknownClassId + "/capture", new byte[0]);
        assertThat(capture.status()).isEqualTo(404);
        assertThat(capture.json().path("error").asText()).isEqualTo("not_found");
    }

    @Test
    void previewWeavesInputBytesAndStoresPlannedSnapshot() throws Exception {
        Method compute = SampleService.class.getMethod("compute", int.class);
        runtime.publish(compute, rule("prev", compute, InvokePhase.BEFORE, "return mock.returnValue(5)"));

        String classId = classId(SampleService.class);
        byte[] inputBytes = get("/v1/classes/" + classId + "/bytecode?kind=INPUT&revision=1").body();

        Resp preview = post("/v1/classes/" + classId + "/preview", inputBytes);
        assertThat(preview.status()).isEqualTo(200);
        JsonNode body = preview.json();
        assertThat(body.path("changed").asBoolean()).isTrue();
        assertThat(body.path("inputHash").asText()).isNotBlank();
        assertThat(body.path("plannedHash").asText()).isNotBlank();
        assertThat(body.path("plannedHash").asText()).isNotEqualTo(body.path("inputHash").asText());
        assertThat(body.path("targetMethodCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(body.path("plannedSizeBytes").asInt()).isGreaterThan(0);

        // Preview decompiles the planned bytes so the caller can read the approximate
        // post-enhancement source without touching the JVM.
        JsonNode decomp = body.path("decompilation");
        assertThat(decomp.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(decomp.path("decompilerName").asText()).isEqualTo("vineflower");
        assertThat(decomp.path("sourceCode").asText()).contains("SampleService");

        // The PLANNED snapshot was stored at the current revision and is fetchable.
        Resp planned = get("/v1/classes/" + classId + "/bytecode?kind=PLANNED&revision=1");
        assertThat(planned.status()).isEqualTo(200);
        assertThat(planned.header("X-Kairo-Hash")).isEqualTo(body.path("plannedHash").asText());
    }

    @Test
    void previewRejectsEmptyAndOversizedBody() throws Exception {
        String classId = classId(SampleService.class);

        // Empty body -> 400 (input bytes are required).
        assertThat(post("/v1/classes/" + classId + "/preview", new byte[0]).status()).isEqualTo(400);

        // Oversized body -> 413 on a server with a tiny request-body cap.
        try (AgentHttpServer tiny = smallLimitServer(new BytecodeApiLimits(8, 8 * 1024 * 1024, 2_000L, 1))) {
            tiny.start();
            String tinyBase = "http://127.0.0.1:" + tiny.port();
            byte[] tooBig = new byte[64];
            Resp resp = send(HttpRequest.newBuilder(URI.create(tinyBase + "/v1/classes/" + classId + "/preview"))
                    .header("X-Agent-Token", TOKEN)
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(tooBig)).build());
            assertThat(resp.status()).isEqualTo(413);
            assertThat(resp.json().path("error").asText()).isEqualTo("payload_too_large");
        }
    }

    @Test
    void diffProducesStructuredResultAndTextFormat() throws Exception {
        Method compute = SampleService.class.getMethod("compute", int.class);
        runtime.publish(compute, rule("diff", compute, InvokePhase.BEFORE, "return mock.returnValue(1)"));

        String classId = classId(SampleService.class);
        byte[] inputBytes = get("/v1/classes/" + classId + "/bytecode?kind=INPUT&revision=1").body();
        post("/v1/classes/" + classId + "/preview", inputBytes); // stores PLANNED@1

        Resp diff = get("/v1/classes/" + classId + "/diff?from=INPUT@1&to=PLANNED@1&format=json");
        assertThat(diff.status()).isEqualTo(200);
        JsonNode body = diff.json();
        assertThat(body.path("identical").asBoolean()).isFalse();
        assertThat(body.path("normalized").asBoolean()).isTrue();
        assertThat(body.path("methodDiffs").isArray()).isTrue();
        assertThat(body.path("methodDiffs").size()).isGreaterThan(0);

        // Both sides carry approximate source for a true Before/After Java view.
        for (String field : List.of("fromDecompilation", "toDecompilation")) {
            JsonNode decomp = body.path(field);
            assertThat(decomp.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(decomp.path("decompilerName").asText()).isEqualTo("vineflower");
            assertThat(decomp.path("sourceCode").asText()).contains("SampleService");
        }
        // The diff fields remain at the top level (not wrapped under "diff").
        assertThat(body.path("classIdentity").path("binaryClassName").asText())
                .isEqualTo(SampleService.class.getName());

        // Identical sides compare identical.
        Resp same = get("/v1/classes/" + classId + "/diff?from=INPUT@1&to=INPUT@1");
        assertThat(same.json().path("identical").asBoolean()).isTrue();

        // Text format renders a human-readable diff.
        Resp text = get("/v1/classes/" + classId + "/diff?from=INPUT@1&to=PLANNED@1&format=text");
        assertThat(text.status()).isEqualTo(200);
        assertThat(text.header("Content-Type")).startsWith("text/plain");
        assertThat(new String(text.body())).contains("bytecode diff");

        // Unsupported format -> 400.
        assertThat(get("/v1/classes/" + classId + "/diff?from=INPUT@1&to=PLANNED@1&format=xml").status())
                .isEqualTo(400);
    }

    @Test
    void missingSnapshotReturns404() throws Exception {
        String classId = classId(SampleService.class);
        // No transform has happened, so revision 1 INPUT does not exist.
        Resp bytecode = get("/v1/classes/" + classId + "/bytecode?kind=INPUT&revision=1");
        assertThat(bytecode.status()).isEqualTo(404);
        assertThat(bytecode.json().path("error").asText()).isEqualTo("not_found");

        Resp diff = get("/v1/classes/" + classId + "/diff?from=INPUT@1&to=PLANNED@1");
        assertThat(diff.status()).isEqualTo(404);

        // Invalid kind/revision are 400, not 404.
        assertThat(get("/v1/classes/" + classId + "/bytecode?kind=BOGUS&revision=1").status()).isEqualTo(400);
        assertThat(get("/v1/classes/" + classId + "/bytecode?kind=INPUT").status()).isEqualTo(400);
        assertThat(get("/v1/classes/" + classId + "/bytecode?kind=INPUT&revision=-1").status()).isEqualTo(400);
    }

    @Test
    void bytecodeResponseEnforcesSizeLimit() throws Exception {
        Method compute = SampleService.class.getMethod("compute", int.class);
        runtime.publish(compute, rule("size", compute, InvokePhase.BEFORE, "return mock.returnValue(3)"));

        String classId = classId(SampleService.class);
        // A tiny response cap (8B) is smaller than any real class file.
        try (AgentHttpServer tiny = smallLimitServer(new BytecodeApiLimits(1024, 8, 2_000L, 1))) {
            tiny.start();
            String tinyBase = "http://127.0.0.1:" + tiny.port();
            Resp resp = send(HttpRequest.newBuilder(URI.create(
                    tinyBase + "/v1/classes/" + classId + "/bytecode?kind=INPUT&revision=1"))
                    .header("X-Agent-Token", TOKEN).GET().build());
            assertThat(resp.status()).isEqualTo(413);
            assertThat(resp.json().path("error").asText()).isEqualTo("payload_too_large");
        }
    }

    @Test
    void sameClassNameInDifferentClassLoadersDoesNotCross() throws Exception {
        Class<?> first = compileAndLoadDuplicate("first");
        Class<?> second = compileAndLoadDuplicate("second");
        Method firstEcho = first.getMethod("echo", String.class);
        runtime.publish(firstEcho, rule("iso", firstEcho, InvokePhase.RETURN, "return mock.returnValue('mocked')"));

        String firstId = classId(first);
        String secondId = classId(second);
        assertThat(firstId).isNotEqualTo(secondId);

        // The transformed loader has revision 1 + history; the other loader is untouched.
        Resp firstHistory = get("/v1/classes/" + firstId + "/transformations");
        assertThat(firstHistory.json().path("currentRevision").path("value").asLong()).isEqualTo(1L);
        assertThat(firstHistory.json().path("history").size()).isGreaterThan(0);

        Resp secondHistory = get("/v1/classes/" + secondId + "/transformations");
        assertThat(secondHistory.json().path("currentRevision").path("value").asLong()).isZero();
        assertThat(secondHistory.json().path("history").size()).isZero();

        // The first loader's INPUT snapshot is not visible under the second loader's classId.
        assertThat(get("/v1/classes/" + secondId + "/bytecode?kind=INPUT&revision=1").status()).isEqualTo(404);
    }

    // ---- helpers ----

    private String classId(Class<?> type) {
        return runtime.loadedClassRepository().classId(type);
    }

    private AgentHttpServer smallLimitServer(BytecodeApiLimits limits) {
        return new AgentHttpServer(runtime, "127.0.0.1", 0,
                new AgentTokenManager(TOKEN, java.time.Duration.ofMinutes(15)), limits);
    }

    private static MockRule rule(String id, Method method, InvokePhase phase, String script) {
        return MockRule.builder()
                .id(id)
                .name(id)
                .target(MethodSelector.builder()
                        .className(method.getDeclaringClass().getName())
                        .classLoaderId(com.example.kairo.core.ClassLoaderIdentity
                                .idOf(method.getDeclaringClass().getClassLoader()))
                        .methodName(method.getName())
                        .methodDescriptor(com.example.kairo.core.MethodDescriptor.of(method))
                        .build())
                .phase(phase)
                .script(script)
                .priority(100)
                .percentage(100)
                .failOpen(true)
                .enabled(true)
                .build();
    }

    /** Encode a classId the same way {@code LoadedClassRepository.classId} does, without loading the class. */
    private static String encodeClassId(String binaryClassName, String classLoaderId) {
        String raw = classLoaderId + "|" + binaryClassName;
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Class<?> compileAndLoadDuplicate(String prefix) throws Exception {
        Path dir = Files.createTempDirectory("dup-" + prefix);
        Path sourceDir = dir.resolve("com/example/duplicate");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("DuplicateService.java"), """
                package com.example.duplicate;
                public class DuplicateService {
                    public String echo(String value) {
                        return "%s-" + value;
                    }
                }
                """.formatted(prefix));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).isNotNull();
        assertThat(compiler.run(null, null, null, "-d", dir.toString(),
                sourceDir.resolve("DuplicateService.java").toString())).isZero();
        URLClassLoader loader = new URLClassLoader(new URL[]{dir.toUri().toURL()},
                ClassLoader.getSystemClassLoader());
        return Class.forName("com.example.duplicate.DuplicateService", true, loader);
    }

    private Resp get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("X-Agent-Token", TOKEN).GET().build());
    }

    private Resp getNoAuth(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET().build());
    }

    private Resp post(String path, byte[] body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("X-Agent-Token", TOKEN)
                .header("Content-Type", "application/octet-stream");
        if (body.length == 0) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofByteArray(body));
        }
        return send(builder.build());
    }

    private Resp postNoAuth(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    private HttpRequest headRequest(String path) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .header("X-Agent-Token", TOKEN).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
    }

    private Resp send(HttpRequest request) throws Exception {
        HttpResponse<byte[]> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        resp.headers().map().forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT),
                v == null || v.isEmpty() ? "" : v.get(0)));
        return new Resp(resp.statusCode(), resp.body(), headers);
    }

    private record Resp(int status, byte[] body, Map<String, String> headers) {
        String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }

        JsonNode json() {
            try {
                return MAPPER.readTree(body);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
