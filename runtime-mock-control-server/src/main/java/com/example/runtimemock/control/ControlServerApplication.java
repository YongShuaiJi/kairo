package com.example.runtimemock.control;

import java.util.concurrent.CountDownLatch;

public final class ControlServerApplication {

    private ControlServerApplication() {
    }

    public static void main(String[] args) throws InterruptedException {
        ControlServerOptions options = ControlServerOptions.parse(args);
        ControlHttpServer server = new ControlHttpServer(options);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "runtime-mock-control-shutdown"));
        server.start();
        System.out.println("Runtime Mock control server started: http://" + options.host() + ":" + server.port());
        new CountDownLatch(1).await();
    }
}
