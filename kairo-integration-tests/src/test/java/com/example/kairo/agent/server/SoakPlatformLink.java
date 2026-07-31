package com.example.kairo.agent.server;

import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.api.snapshot.AgentRuntimeSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Test-harness bridge for M2-D. It exercises the real agent-side Platform command channel
 * without exposing the package-private {@link PlatformCommandPoller} in production code.
 * Disconnecting closes only the command channel: the {@link AgentRuntime} and the enhanced
 * target JVM stay alive. Reconnecting creates a fresh channel and obtains the actual runtime
 * state through the real {@code REFRESH_RUNTIME_STATE} command path.
 */
public final class SoakPlatformLink implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final AgentRuntime runtime;
    private final String agentId;
    private final String processStartId;
    private PlatformCommandPoller poller;

    public SoakPlatformLink(AgentRuntime runtime, String agentId, String processStartId) {
        this.runtime = runtime;
        this.agentId = agentId;
        this.processStartId = processStartId;
        reconnect();
    }

    public void disconnect() {
        if (poller != null) {
            poller.close();
            poller = null;
        }
    }

    public void reconnect() {
        if (poller != null) {
            throw new IllegalStateException("platform link is already connected");
        }
        poller = new PlatformCommandPoller(runtime,
                AgentLaunchConfig.parse("platformAgentId=" + agentId
                        + ",platformProcessStartId=" + processStartId),
                () -> { });
    }

    public AgentRuntimeSnapshot refreshRuntimeState() {
        if (poller == null) {
            throw new IllegalStateException("platform link is disconnected");
        }
        Map<String, Object> result = poller.execute(MAPPER.createObjectNode()
                .set("payload", MAPPER.createObjectNode().put("commandType", "REFRESH_RUNTIME_STATE")));
        return MAPPER.convertValue(result, AgentRuntimeSnapshot.class);
    }

    @Override
    public void close() {
        disconnect();
    }
}
