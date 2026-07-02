package com.example.runtimemock.agent.server;

import com.example.runtimemock.agent.core.AgentCore;

import java.util.concurrent.atomic.AtomicBoolean;

public final class AgentCoreHandle implements AutoCloseable {

    private final AgentHttpServer httpServer;
    private final PlatformCommandPoller platformCommandPoller;
    private final PlatformRecordingUploader platformRecordingUploader;
    private final AgentTokenManager tokenManager;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    AgentCoreHandle(AgentHttpServer httpServer, PlatformCommandPoller platformCommandPoller,
                    PlatformRecordingUploader platformRecordingUploader, AgentTokenManager tokenManager) {
        this.httpServer = httpServer;
        this.platformCommandPoller = platformCommandPoller;
        this.platformRecordingUploader = platformRecordingUploader;
        this.tokenManager = tokenManager;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (platformCommandPoller != null) {
            platformCommandPoller.close();
        }
        if (platformRecordingUploader != null) {
            platformRecordingUploader.close();
        }
        tokenManager.close();
        httpServer.close();
        AgentCore.stop();
    }
}
