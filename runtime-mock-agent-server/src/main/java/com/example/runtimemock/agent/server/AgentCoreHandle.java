package com.example.runtimemock.agent.server;

import com.example.runtimemock.agent.core.AgentCore;

public final class AgentCoreHandle implements AutoCloseable {

    private final AgentHttpServer httpServer;
    private final PlatformCommandPoller platformCommandPoller;

    AgentCoreHandle(AgentHttpServer httpServer, PlatformCommandPoller platformCommandPoller) {
        this.httpServer = httpServer;
        this.platformCommandPoller = platformCommandPoller;
    }

    @Override
    public void close() {
        if (platformCommandPoller != null) {
            platformCommandPoller.close();
        }
        httpServer.close();
        AgentCore.stop();
    }
}
