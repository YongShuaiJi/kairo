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
        try {
            runtime.loadMode(loadMode);
            AgentTokenManager tokenManager = new AgentTokenManager(config.token(), config.tokenTtl());
            AgentHttpServer server = new AgentHttpServer(runtime, config.host(), config.port(), tokenManager);
            server.start();
            PlatformCommandPoller poller = null;
            PlatformRecordingUploader recordingUploader = null;
            if (config.platformPollingEnabled()) {
                recordingUploader = new PlatformRecordingUploader(runtime, config);
                runtime.recordingSink(recordingUploader);
                recordingUploader.start();
                poller = new PlatformCommandPoller(runtime, config);
                poller.start();
            }
            final Path[] registration = new Path[1];
            tokenManager.start(token -> registration[0] = AgentRegistrationWriter.write(
                    config.registrationDir(), config.tokenFile(), runtime.jvmInfo(), server.port(),
                    token.token(), token.expiresAt(), AgentHttpServer.PROTOCOL_VERSION));
            runtime.recordEvent("agent.register", "system", null, null,
                    "Agent registered at " + registration[0]);
            return new AgentCoreHandle(server, poller, recordingUploader, tokenManager);
        } catch (RuntimeException e) {
            AgentCore.stop();
            throw e;
        }
    }
}
