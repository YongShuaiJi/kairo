package com.example.kairo.agent.server;

import com.example.bytecode.SampleService;
import com.example.kairo.agent.core.AgentRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformBytecodeCommandTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void executesAllFiveDiagnosticsWithoutLocalHttpOrToken() {
        AgentRuntime runtime = new AgentRuntime(ByteBuddyAgent.install());
        runtime.start();
        try (PlatformCommandPoller poller = new PlatformCommandPoller(
                runtime, AgentLaunchConfig.parse(""), () -> { })) {
            String classId = runtime.loadedClassRepository().classId(SampleService.class);
            Map<String, Object> capture = poller.execute(command("BYTECODE_CAPTURE", Map.of("classId", classId)));
            assertThat(capture).containsEntry("captured", true);

            Map<String, Object> transformations = poller.execute(
                    command("BYTECODE_TRANSFORMATIONS", Map.of("classId", classId)));
            assertThat(transformations).containsKeys("classIdentity", "currentRevision", "history");

            Map<String, Object> get = poller.execute(command("BYTECODE_GET",
                    Map.of("classId", classId, "kind", "APPLIED", "revision", 0)));
            assertThat(get).containsKeys("bytecodeBase64Url", "hash", "sizeBytes");

            Map<String, Object> preview = poller.execute(command("BYTECODE_PREVIEW",
                    Map.of("classId", classId, "bytecodeBase64Url", get.get("bytecodeBase64Url"))));
            assertThat(preview).containsKeys("inputHash", "changed", "diagnostics");

            Map<String, Object> diff = poller.execute(command("BYTECODE_DIFF", Map.of(
                    "classId", classId, "fromKind", "APPLIED", "fromRevision", 0,
                    "toKind", "APPLIED", "toRevision", 0)));
            assertThat(diff).containsEntry("identical", true).containsEntry("normalized", true);
        } finally {
            runtime.close();
        }
    }

    private com.fasterxml.jackson.databind.JsonNode command(String type, Map<String, Object> values) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(values);
        payload.put("commandType", type);
        return mapper.valueToTree(Map.of("payload", payload));
    }
}
