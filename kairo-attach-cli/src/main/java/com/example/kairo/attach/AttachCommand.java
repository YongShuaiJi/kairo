package com.example.kairo.attach;

public final class AttachCommand {

    private AttachCommand() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0])) {
            printUsage();
            return;
        }
        if ("exec".equals(args[0])) {
            // Long-running demo attach executor: register, poll ATTACH_AGENT/RELOAD_AGENT,
            // ACK and serve /health. Migrated from the former kairo-sidecar module.
            AttachExecutorServer.main(stripFirst(args));
            return;
        }
        if ("--list".equals(args[0])) {
            ProcessDiscovery.listJavaProcesses().forEach(System.out::println);
            return;
        }
        AttachOptions options = AttachOptions.parse(args);
        attach(options);
        System.out.println("Attached Kairo agent to pid " + options.pid()
                + ", health: http://" + options.host() + ":" + options.port() + "/health");
    }

    private static String[] stripFirst(String[] args) {
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        return rest;
    }

    static void attach(AttachOptions options) throws Exception {
        Class<?> vmType = Class.forName("com.sun.tools.attach.VirtualMachine");
        Object vm = vmType.getMethod("attach", String.class).invoke(null, options.pid());
        try {
            vmType.getMethod("loadAgent", String.class, String.class)
                    .invoke(vm, options.agentJar().toString(), options.agentArgs());
        } finally {
            vmType.getMethod("detach").invoke(vm);
        }
    }

    private static void printUsage() {
        System.out.println("""
                Usage:
                  java -jar kairo-attach.jar --pid <pid> --agent <kairo-agent.jar> [--core-jar <core.jar>] [--bootstrap-jar <bootstrap-api.jar>] [--host 127.0.0.1] [--port 18080] [--token token]
                  java -jar kairo-attach.jar --list
                  java -jar kairo-attach.jar exec   # run the demo attach executor (register/poll/ACK/health)
                """);
    }
}
