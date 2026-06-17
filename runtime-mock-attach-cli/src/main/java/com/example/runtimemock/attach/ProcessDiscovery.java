package com.example.runtimemock.attach;

import java.util.Comparator;
import java.util.List;

public final class ProcessDiscovery {

    private ProcessDiscovery() {
    }

    public static List<String> listJavaProcesses() {
        return ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .map(ProcessDiscovery::describe)
                .filter(description -> description.contains("java") || description.contains("surefire"))
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private static String describe(ProcessHandle handle) {
        ProcessHandle.Info info = handle.info();
        String command = info.command().orElse("");
        String arguments = info.arguments().map(args -> String.join(" ", args)).orElse("");
        return handle.pid() + " " + command + " " + arguments;
    }
}
