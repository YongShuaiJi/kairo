package com.example.kairo.attach;

public final class AttachCommand {

    private AttachCommand() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0])) {
            printUsage();
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
                """);
    }
}
