package com.example.runtimemock.agent.server;

import com.example.runtimemock.agent.core.AgentCore;

public final class AgentCoreHandle implements AutoCloseable {

    private final AgentHttpServer httpServer;
    private final PlatformCommandPoller platformCommandPoller;
    private final AgentTokenManager tokenManager;

    AgentCoreHandle(AgentHttpServer httpServer, PlatformCommandPoller platformCommandPoller,
                    AgentTokenManager tokenManager) {
        this.httpServer = httpServer;
        this.platformCommandPoller = platformCommandPoller;
        this.tokenManager = tokenManager;
    }

    @Override
    public void close() {
        if (platformCommandPoller != null) {
            platformCommandPoller.close();
        }
        tokenManager.close();
        httpServer.close();
        AgentCore.stop();
    }
}
