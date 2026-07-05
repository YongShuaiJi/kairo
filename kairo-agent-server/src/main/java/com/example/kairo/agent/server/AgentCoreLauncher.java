package com.example.kairo.agent.server;

import com.example.kairo.agent.core.AgentCore;
import com.example.kairo.agent.core.AgentRuntime;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

public final class AgentCoreLauncher {

    private AgentCoreLauncher() {
    }

    public static AutoCloseable start(String agentArgs, Instrumentation instrumentation, String loadMode) {
        AgentLaunchConfig config = AgentLaunchConfig.parse(agentArgs);
        AgentRuntime runtime = AgentCore.start(agentArgs, instrumentation);
        try {
            AtomicReference<AgentCoreHandle> handleRef = new AtomicReference<>();
            runtime.loadMode(loadMode);
            AgentTokenManager tokenManager = new AgentTokenManager(config.token(), config.tokenTtl());
            AgentHttpServer server = new AgentHttpServer(runtime, config.host(), config.port(), tokenManager);
            server.start();
            AgentLaunchConfig effectiveConfig = config;
            if (config.platformRegistrationEnabled()) {
                PlatformAgentRegistrationClient.Registration registration =
                        new PlatformAgentRegistrationClient(config).register(runtime.jvmInfo(), server.port());
                effectiveConfig = config.withPlatformAgentId(registration.agentId());
                runtime.recordEvent("platform.agent.register", "platform", null, registration.instanceId(),
                        "Agent registered as " + registration.agentId());
            }
            PlatformCommandPoller poller = null;
            PlatformRecordingUploader recordingUploader = null;
            if (effectiveConfig.platformPollingEnabled()) {
                recordingUploader = new PlatformRecordingUploader(runtime, effectiveConfig);
                runtime.recordingSink(recordingUploader);
                recordingUploader.start();
                poller = new PlatformCommandPoller(runtime, effectiveConfig, () -> {
                    AgentCoreHandle handle = handleRef.get();
                    if (handle != null) {
                        handle.close();
                    }
                });
                poller.start();
            }
            final Path[] registration = new Path[1];
            tokenManager.start(token -> registration[0] = AgentRegistrationWriter.write(
                    config.registrationDir(), config.tokenFile(), runtime.jvmInfo(), server.port(),
                    token.token(), token.expiresAt(), AgentHttpServer.PROTOCOL_VERSION));
            runtime.recordEvent("agent.register", "system", null, null,
                    "Agent registered at " + registration[0]);
            AgentCoreHandle handle = new AgentCoreHandle(server, poller, recordingUploader, tokenManager);
            handleRef.set(handle);
            return handle;
        } catch (RuntimeException e) {
            AgentCore.stop();
            throw e;
        }
    }
}
