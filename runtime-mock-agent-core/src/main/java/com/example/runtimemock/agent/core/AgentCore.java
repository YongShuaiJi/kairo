package com.example.runtimemock.agent.core;

import java.lang.instrument.Instrumentation;

public final class AgentCore {

    private static volatile AgentRuntime currentRuntime;

    private AgentCore() {
    }

    public static synchronized AgentRuntime start(String agentArgs, Instrumentation instrumentation) {
        if (currentRuntime != null) {
            return currentRuntime;
        }
        AgentRuntime runtime = new AgentRuntime(instrumentation);
        runtime.start();
        currentRuntime = runtime;
        return runtime;
    }

    public static synchronized void stop() {
        AgentRuntime runtime = currentRuntime;
        if (runtime != null) {
            runtime.close();
            currentRuntime = null;
        }
    }

    public static AgentRuntime currentRuntime() {
        return currentRuntime;
    }
}
