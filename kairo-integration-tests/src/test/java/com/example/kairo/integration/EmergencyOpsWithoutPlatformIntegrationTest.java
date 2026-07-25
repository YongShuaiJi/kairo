package com.example.kairo.integration;

import com.example.demo.OrderService;
import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.agent.server.AgentHttpServer;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.MockRule;
import com.example.kairo.api.snapshot.AgentRuntimeSnapshot;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.core.MethodDescriptor;
import com.example.kairo.ops.OpsCommand;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M1-F &sect;8.6 item 3: while the Platform is unavailable, {@code kairo-ops disable-all /
 * reset-class / reset-all} still operates against the loopback Agent API (the Agent's local management
 * port) and writes a local audit record. No Platform connection is involved at all: the test
 * constructs only a real {@link AgentRuntime} + {@link AgentHttpServer} (no poller, no Platform URL)
 * and drives {@link OpsCommand} against the loopback port.
 *
 * <p>Each emergency op also marks the agent emergency-held (item 4): the snapshot's
 * {@code emergency=true} is what lets Platform reconciliation defer once it reconnects. The local
 * audit is redirected to a temp file via {@code kairo.ops.audit.path} so the test does not touch the
 * operator's real {@code ~/.kairo/ops-audit.jsonl}.
 */
class EmergencyOpsWithoutPlatformIntegrationTest {

    private static final String TOKEN = "loopback-test-token";

    private AgentRuntime runtime;
    private AgentHttpServer server;
    private Path auditFile;

    @BeforeEach
    void setUp() throws Exception {
        Instrumentation instrumentation = ByteBuddyAgent.install();
        runtime = new AgentRuntime(instrumentation);
        runtime.start();
        publishRule();
        server = new AgentHttpServer(runtime, "127.0.0.1", 0, TOKEN);
        server.start();
        auditFile = Files.createTempFile("kairo-ops-audit", ".jsonl");
        System.setProperty("kairo.ops.audit.path", auditFile.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("kairo.ops.audit.path");
        if (server != null) {
            server.close();
        }
        if (runtime != null) {
            runtime.close();
        }
        if (auditFile != null) {
            Files.deleteIfExists(auditFile);
        }
    }

    @Test
    void disableAllWorksViaLoopbackAndAuditsWithoutPlatform() throws Exception {
        int exit = OpsCommand.execute(args("disable-all", server.port()));
        assertThat(exit).isZero();

        AgentRuntimeSnapshot snapshot = runtime.snapshotRuntimeState("agent-emergency", "psid-emergency");
        assertThat(snapshot.disabled()).isTrue();
        assertThat(snapshot.emergency()).isTrue();
        assertAudited("disable-all");

        int resumeExit = OpsCommand.execute(args("enable-all", server.port()));
        assertThat(resumeExit).isZero();
        AgentRuntimeSnapshot resumed = runtime.snapshotRuntimeState("agent-emergency", "psid-emergency");
        assertThat(resumed.disabled()).isFalse();
        assertThat(resumed.emergency()).isFalse();
        assertAudited("enable-all");
    }

    @Test
    void resetAllWorksViaLoopbackAndAuditsWithoutPlatform() throws Exception {
        int exit = OpsCommand.execute(args("reset-all", server.port()));
        assertThat(exit).isZero();

        AgentRuntimeSnapshot snapshot = runtime.snapshotRuntimeState("agent-emergency", "psid-emergency");
        assertThat(snapshot.chains()).as("reset-all cleared every chain").isEmpty();
        assertThat(snapshot.emergency()).isTrue();
        assertAudited("reset-all");
    }

    @Test
    void resetClassWorksViaLoopbackAndAuditsWithoutPlatform() throws Exception {
        String className = OrderService.class.getName();
        int exit = OpsCommand.execute(argsWithClass("reset-class", server.port(), className));
        assertThat(exit).isZero();

        AgentRuntimeSnapshot snapshot = runtime.snapshotRuntimeState("agent-emergency", "psid-emergency");
        assertThat(snapshot.rules()).as("reset-class removed the targeted rule").isEmpty();
        assertThat(snapshot.emergency()).isTrue();
        assertAudited("reset-class");
    }

    private void publishRule() throws Exception {
        Method method = OrderService.class.getMethod("createOrder", com.example.demo.CreateOrderRequest.class);
        MockRule rule = MockRule.builder()
                .id("rule-emergency")
                .name("rule-emergency")
                .target(MethodSelector.builder()
                        .className(method.getDeclaringClass().getName())
                        .classLoaderId(ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()))
                        .methodName(method.getName())
                        .methodDescriptor(MethodDescriptor.of(method))
                        .build())
                .phase(InvokePhase.BEFORE)
                .script("return mock.proceed()")
                .priority(100)
                .percentage(100)
                .build();
        runtime.publish(method, rule);
        // The rule is applied; a snapshot now carries the chain.
        assertThat(runtime.snapshotRuntimeState("a", "p").chains()).isNotEmpty();
    }

    private static String[] args(String command, int port) {
        return new String[]{command, "--url", "http://127.0.0.1:" + port, "--token", TOKEN,
                "--reason", "emergency-recovery", "--event", "evt-" + UUID.randomUUID()};
    }

    private static String[] argsWithClass(String command, int port, String classId) {
        return new String[]{command, "--url", "http://127.0.0.1:" + port, "--token", TOKEN,
                "--class-id", classId, "--reason", "emergency-recovery", "--event", "evt-" + UUID.randomUUID()};
    }

    private void assertAudited(String command) throws Exception {
        String audit = Files.readString(auditFile, StandardCharsets.UTF_8);
        assertThat(audit).as("local audit recorded the %s op", command)
                .contains("\"command\":\"" + command + "\"")
                .contains("\"status\":200");
    }
}
