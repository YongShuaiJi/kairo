package com.example.kairo.platform.health;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * V1.7 M4-A &sect;11.1: a minimal Redis-compatible TCP server used by {@code RedisHealthRecoveryIntegrationTest}
 * to prove real Redis readiness failure <em>and recovery</em> through the real Lettuce client. A fixed
 * permanently closed port (like 127.0.0.1:1) cannot be restarted, so it cannot prove recovery; a mocked
 * {@code HealthIndicator} proves nothing about the real client. This server speaks just enough RESP2 to
 * satisfy Lettuce's connection handshake and answer {@code PING}, and it can be stopped and restarted on the
 * <em>same</em> configured address so the test observes the genuine disconnect/reconnect lifecycle.
 *
 * <p>Handshake coverage (Lettuce 6.x default): Lettuce first sends {@code HELLO 3 ...}; this server replies
 * with {@code -ERR unknown command\r\n}, which Lettuce recognises as a pre-HELLO (RESP2) server and falls back
 * to RESP2 without re-sending HELLO. It then issues {@code CLIENT SETINFO ...} and {@code PING} on connect;
 * both are answered. The {@code KairoRedisHealthIndicator} issues a further {@code PING} per probe.
 *
 * <p>This is test-scope only and intentionally minimal: it is not a Redis replacement. It binds to loopback
 * only and never touches the real network.
 */
final class EmbeddedRedisServer {

    private final int port;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final Set<Socket> clients = new HashSet<>();

    EmbeddedRedisServer() throws IOException {
        this(0);
    }

    EmbeddedRedisServer(int port) throws IOException {
        this.port = bind(port);
    }

    /** The loopback port this server listens on (an OS-chosen ephemeral port when constructed with 0). */
    int port() {
        return port;
    }

    /** True while the server socket is open and accepting. */
    synchronized boolean isRunning() {
        return serverSocket != null && !serverSocket.isClosed();
    }

    /** Stop accepting and forcibly close every connection so connected clients see a real disconnect. */
    synchronized void stop() {
        if (serverSocket == null) {
            return;
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // closing the listening socket is best-effort
        }
        serverSocket = null;
        closeClients();
    }

    /** Re-bind to the same port and resume accepting (the recovery step that a closed port cannot do). */
    synchronized void restart() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) {
            return;
        }
        // SO_REUSEADDR so the same port can be re-bound immediately after stop(); retry to absorb the
        // brief window in which the OS has not yet released the listening socket.
        IOException last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                bind(port);
                return;
            } catch (IOException e) {
                last = e;
                sleepQuietly(25L);
            }
        }
        throw last;
    }

    private int bind(int port) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress("127.0.0.1", port));
        this.serverSocket = socket;
        startAcceptLoop();
        return socket.getLocalPort();
    }

    private void startAcceptLoop() {
        final ServerSocket listening = serverSocket;
        acceptThread = new Thread(() -> acceptLoop(listening), "embedded-redis-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop(ServerSocket listening) {
        while (true) {
            Socket client;
            try {
                client = listening.accept();
            } catch (IOException closed) {
                // server socket closed by stop(): stop accepting.
                return;
            }
            synchronized (this) {
                if (serverSocket == null) {
                    closeQuietly(client);
                    return;
                }
                clients.add(client);
            }
            Thread handler = new Thread(() -> handle(client), "embedded-redis-client");
            handler.setDaemon(true);
            handler.start();
        }
    }

    private void handle(Socket client) {
        try (Socket socket = client) {
            socket.setTcpNoDelay(true);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
            String line;
            while ((line = reader.readLine()) != null) {
                byte[] reply = reply(line, reader);
                if (reply == null) {
                    return;
                }
                out.write(reply);
                out.flush();
            }
        } catch (IOException closed) {
            // client disconnected (either side); handler exits.
        } finally {
            synchronized (this) {
                clients.remove(client);
            }
        }
    }

    /** Parse one RESP command (array or inline) and return the bytes to write back, or null to drop. */
    private static byte[] reply(String firstLine, BufferedReader reader) throws IOException {
        String[] args;
        if (firstLine.startsWith("*")) {
            int argc;
            try {
                argc = Integer.parseInt(firstLine.substring(1));
            } catch (NumberFormatException e) {
                return "-ERR protocol error\r\n".getBytes(StandardCharsets.US_ASCII);
            }
            args = new String[argc];
            for (int i = 0; i < argc; i++) {
                String header = reader.readLine();
                if (header == null) {
                    return null;
                }
                // header is "$<len>"; read exactly that many bytes followed by CRLF. The command bodies here
                // are ASCII without embedded CRLF, so a line read is equivalent to a length read.
                int len = header.startsWith("$") ? parseIntLen(header.substring(1)) : 0;
                String body = reader.readLine();
                if (body == null) {
                    return null;
                }
                args[i] = body;
                if (len < 0 && reader.readLine() != null) {
                    // unexpected trailing data; tolerate
                }
            }
        } else if (firstLine.startsWith("$")) {
            String body = reader.readLine();
            args = body == null ? new String[0] : new String[]{body};
        } else {
            args = firstLine.isEmpty() ? new String[0] : firstLine.split("\\s+");
        }
        if (args.length == 0) {
            return "+OK\r\n".getBytes(StandardCharsets.US_ASCII);
        }
        String command = args[0].toUpperCase();
        switch (command) {
            case "PING":
                return "+PONG\r\n".getBytes(StandardCharsets.US_ASCII);
            case "CLIENT":
            case "SELECT":
            case "AUTH":
            case "HELLO":
                // HELLO returns an error so Lettuce falls back to RESP2 (it does not resend HELLO).
                return command.equals("HELLO")
                        ? "-ERR unknown command\r\n".getBytes(StandardCharsets.US_ASCII)
                        : "+OK\r\n".getBytes(StandardCharsets.US_ASCII);
            case "COMMAND":
            case "CONFIG":
                return "*0\r\n".getBytes(StandardCharsets.US_ASCII);
            case "INFO":
                return "$0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            default:
                return "-ERR unknown command\r\n".getBytes(StandardCharsets.US_ASCII);
        }
    }

    private static int parseIntLen(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private synchronized void closeClients() {
        for (Socket client : clients) {
            closeQuietly(client);
        }
        clients.clear();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
