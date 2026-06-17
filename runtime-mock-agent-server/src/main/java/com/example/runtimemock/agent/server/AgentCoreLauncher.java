package com.example.runtimemock.agent.server;

import com.example.runtimemock.agent.core.AgentCore;
import com.example.runtimemock.agent.core.AgentRuntime;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;

public final class AgentCoreLauncher {

    private AgentCoreLauncher() {
    }

    public static AutoCloseable start(String agentArgs, Instrumentation instrumentation, String loadMode) {
        AgentLaunchConfig config = AgentLaunchConfig.parse(agentArgs);
        AgentRuntime runtime = AgentCore.start(agentArgs, instrumentation);
        runtime.loadMode(loadMode);
        AgentHttpServer server = new AgentHttpServer(runtime, config.host(), config.port(), config.token());
        server.start();
        PlatformCommandPoller poller = null;
        if (config.platformPollingEnabled()) {
            poller = new PlatformCommandPoller(runtime, config);
            poller.start();
        }
        Path registration = AgentRegistrationWriter.write(
                config.registrationDir(),
                config.tokenFile(),
                runtime.jvmInfo(),
                server.port(),
                config.token(),
                config.tokenTtl(),
                AgentHttpServer.PROTOCOL_VERSION
        );
        runtime.recordEvent("agent.register", "system", null, null,
                "Agent registered at " + registration);
        return new AgentCoreHandle(server, poller);
    }
}
