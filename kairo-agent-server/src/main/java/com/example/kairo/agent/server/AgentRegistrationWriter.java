package com.example.kairo.agent.server;

import com.example.kairo.agent.core.JvmInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;

final class AgentRegistrationWriter {

    private static final Path DEFAULT_DIR = Path.of("/var/run/kairo/agents");

    private AgentRegistrationWriter() {
    }

    static Path write(Path requestedDir, Path requestedTokenFile, JvmInfo jvmInfo, int port,
                      String token, Duration tokenTtl, String protocolVersion) {
        return write(requestedDir, requestedTokenFile, jvmInfo, port, token,
                Instant.now().plus(tokenTtl), protocolVersion);
    }

    static Path write(Path requestedDir, Path requestedTokenFile, JvmInfo jvmInfo, int port,
                      String token, Instant expiresAt, String protocolVersion) {
        Path directory = writableDirectory(requestedDir);
        long pid = jvmInfo.pid();
        Path tokenFile = requestedTokenFile == null
                ? directory.resolve("agent-" + pid + ".token")
                : requestedTokenFile.toAbsolutePath().normalize();
        writeToken(tokenFile, token, expiresAt);
        Path registrationFile = directory.resolve("agent-" + pid + ".properties");
        Properties properties = new Properties();
        properties.setProperty("pid", Long.toString(pid));
        properties.setProperty("applicationName", jvmInfo.applicationName());
        properties.setProperty("jvmStartTimeMillis", Long.toString(jvmInfo.startTimeMillis()));
        properties.setProperty("host", jvmInfo.host());
        properties.setProperty("port", Integer.toString(port));
        properties.setProperty("version", jvmInfo.agentVersion());
        properties.setProperty("protocolVersion", protocolVersion);
        properties.setProperty("status", jvmInfo.status());
        properties.setProperty("loadMode", jvmInfo.loadMode());
        properties.setProperty("tokenFile", tokenFile.toString());
        properties.setProperty("updatedAt", Instant.now().toString());
        try (OutputStream outputStream = Files.newOutputStream(registrationFile)) {
            properties.store(outputStream, "kairo agent registration");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write agent registration file: " + registrationFile, e);
        }
        restrictOwnerOnly(registrationFile);
        return registrationFile;
    }

    private static Path writableDirectory(Path requestedDir) {
        Path directory = requestedDir == null ? DEFAULT_DIR : requestedDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory);
            return directory;
        } catch (IOException firstFailure) {
            if (requestedDir != null) {
                throw new IllegalStateException("Cannot create agent registration directory: " + directory, firstFailure);
            }
            Path fallback = Path.of(System.getProperty("java.io.tmpdir"), "kairo", "agents");
            try {
                Files.createDirectories(fallback);
                return fallback;
            } catch (IOException secondFailure) {
                throw new IllegalStateException("Cannot create fallback agent registration directory: " + fallback,
                        secondFailure);
            }
        }
    }

    private static void writeToken(Path tokenFile, String token, Instant expiresAt) {
        try {
            Files.createDirectories(tokenFile.getParent());
            String body = "token=" + token + "\n"
                    + "expiresAt=" + expiresAt + "\n";
            Path temporary = tokenFile.resolveSibling(tokenFile.getFileName() + ".tmp");
            Files.writeString(temporary, body, StandardCharsets.UTF_8);
            restrictOwnerOnly(temporary);
            try {
                Files.move(temporary, tokenFile,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, tokenFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            restrictOwnerOnly(tokenFile);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write agent token file: " + tokenFile, e);
        }
    }

    private static void restrictOwnerOnly(Path file) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            );
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystems are supported for local development.
        }
    }
}
