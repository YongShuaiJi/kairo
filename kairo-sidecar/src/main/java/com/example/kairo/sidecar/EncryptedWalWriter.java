package com.example.kairo.sidecar;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class EncryptedWalWriter implements AutoCloseable {

    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;
    private static final long DEFAULT_MAX_WAL_BYTES = 10L * 1024 * 1024 * 1024;
    private static final int DEFAULT_MAX_EVENT_BYTES = 1024 * 1024;
    private static final int DEFAULT_QUEUE_CAPACITY = 4096;

    private final Path walFile;
    private final SecretKey key;
    private final Clock clock;
    private final SecureRandom random;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong reservedBytes = new AtomicLong();
    private final AtomicLong inFlightWrites = new AtomicLong();
    private final long maxWalBytes;
    private final int maxEventBytes;
    private final BlockingQueue<PendingWrite> queue;
    private final Map<String, WalAppendResult> deduplication = new ConcurrentHashMap<>();
    private final Thread writerThread;
    private volatile boolean closed;
    private volatile RuntimeException writerFailure;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public EncryptedWalWriter(Path directory, SecretKey key) {
        this(directory, key, Clock.systemUTC(), new SecureRandom(),
                DEFAULT_MAX_WAL_BYTES, DEFAULT_MAX_EVENT_BYTES, DEFAULT_QUEUE_CAPACITY);
    }

    EncryptedWalWriter(Path directory, SecretKey key, Clock clock, SecureRandom random) {
        this(directory, key, clock, random,
                DEFAULT_MAX_WAL_BYTES, DEFAULT_MAX_EVENT_BYTES, DEFAULT_QUEUE_CAPACITY);
    }

    EncryptedWalWriter(Path directory, SecretKey key, Clock clock, SecureRandom random,
                       long maxWalBytes, int maxEventBytes, int queueCapacity) {
        this.walFile = directory.resolve("kairo-recording.wal");
        this.key = key;
        this.clock = clock;
        this.random = random;
        this.maxWalBytes = maxWalBytes;
        this.maxEventBytes = maxEventBytes;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        try {
            Files.createDirectories(directory);
            reservedBytes.set(Files.exists(walFile) ? Files.size(walFile) : 0L);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create WAL directory: " + directory, e);
        }
        this.writerThread = new Thread(this::writeLoop, "kairo-encrypted-wal");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    public WalAppendResult append(Object maskedPayload) {
        ensureOpen();
        try {
            byte[] plaintext = mapper.writeValueAsBytes(maskedPayload);
            if (plaintext.length > maxEventBytes) {
                throw new IllegalArgumentException("WAL event exceeds " + maxEventBytes + " bytes");
            }
            String payloadHash = sha256(plaintext);
            WalAppendResult existing = deduplication.get(payloadHash);
            if (existing != null) {
                return new WalAppendResult(existing.sequence(), existing.timestamp(), payloadHash,
                        existing.plaintextBytes(), existing.ciphertextBytes(), true);
            }
            long nextSequence = sequence.incrementAndGet();
            Instant timestamp = clock.instant();
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            byte[] ciphertext = encrypt(nonce, plaintext, associatedData(nextSequence, timestamp, payloadHash));

            Map<String, Object> line = new LinkedHashMap<>();
            line.put("sequence", nextSequence);
            line.put("timestamp", timestamp.toString());
            line.put("payloadHash", payloadHash);
            line.put("nonce", Base64.getEncoder().encodeToString(nonce));
            line.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
            String encodedLine = mapper.writeValueAsString(line) + System.lineSeparator();
            long lineBytes = encodedLine.getBytes(StandardCharsets.UTF_8).length;
            reserve(lineBytes);
            WalAppendResult result = new WalAppendResult(
                    nextSequence, timestamp, payloadHash, plaintext.length, ciphertext.length, false);
            WalAppendResult raced = deduplication.putIfAbsent(payloadHash, result);
            if (raced != null) {
                reservedBytes.addAndGet(-lineBytes);
                return new WalAppendResult(raced.sequence(), raced.timestamp(), payloadHash,
                        raced.plaintextBytes(), raced.ciphertextBytes(), true);
            }
            if (!queue.offer(new PendingWrite(encodedLine, lineBytes), 100, TimeUnit.MILLISECONDS)) {
                deduplication.remove(payloadHash, result);
                reservedBytes.addAndGet(-lineBytes);
                throw new IllegalStateException("WAL queue is full");
            }
            return result;
        } catch (Exception e) {
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Cannot append encrypted WAL record", e);
        }
    }

    public synchronized List<WalRecord> readAll() {
        try {
            flush();
            if (!Files.exists(walFile)) {
                return List.of();
            }
            List<WalRecord> records = new ArrayList<>();
            for (String line : Files.readAllLines(walFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> row = mapper.readValue(line, new TypeReference<>() {
                });
                long rowSequence = ((Number) row.get("sequence")).longValue();
                Instant rowTimestamp = Instant.parse(String.valueOf(row.get("timestamp")));
                String payloadHash = String.valueOf(row.get("payloadHash"));
                byte[] nonce = Base64.getDecoder().decode(String.valueOf(row.get("nonce")));
                byte[] ciphertext = Base64.getDecoder().decode(String.valueOf(row.get("ciphertext")));
                byte[] plaintext = decrypt(nonce, ciphertext, associatedData(rowSequence, rowTimestamp, payloadHash));
                if (!sha256(plaintext).equals(payloadHash)) {
                    throw new IllegalStateException("WAL payload hash mismatch at sequence " + rowSequence);
                }
                Object payload = mapper.readValue(plaintext, Object.class);
                records.add(new WalRecord(rowSequence, rowTimestamp, payloadHash, payload));
            }
            return List.copyOf(records);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read encrypted WAL", e);
        }
    }

    public Path walFile() {
        return walFile;
    }

    private void reserve(long bytes) {
        while (true) {
            long current = reservedBytes.get();
            if (current + bytes > maxWalBytes) {
                throw new IllegalStateException("WAL quota exceeded");
            }
            if (reservedBytes.compareAndSet(current, current + bytes)) {
                return;
            }
        }
    }

    private void writeLoop() {
        while (!closed || !queue.isEmpty()) {
            try {
                PendingWrite pending = queue.poll(100, TimeUnit.MILLISECONDS);
                if (pending == null) {
                    continue;
                }
                inFlightWrites.incrementAndGet();
                try {
                    Files.writeString(walFile, pending.line(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } finally {
                    inFlightWrites.decrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                writerFailure = new IllegalStateException("Cannot append encrypted WAL record", e);
                return;
            }
        }
    }

    private void flush() {
        while (!queue.isEmpty() || inFlightWrites.get() > 0) {
            ensureHealthy();
            Thread.onSpinWait();
        }
        ensureHealthy();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("WAL writer is closed");
        }
        ensureHealthy();
    }

    private void ensureHealthy() {
        if (writerFailure != null) {
            throw writerFailure;
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            writerThread.join(5_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ensureHealthy();
        try {
            key.destroy();
        } catch (Exception ignored) {
            // Some providers expose Destroyable but do not support explicit destruction.
        }
    }

    private byte[] encrypt(byte[] nonce, byte[] plaintext, byte[] associatedData) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(associatedData);
        return cipher.doFinal(plaintext);
    }

    private byte[] decrypt(byte[] nonce, byte[] ciphertext, byte[] associatedData) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(associatedData);
        return cipher.doFinal(ciphertext);
    }

    private byte[] associatedData(long recordSequence, Instant timestamp, String payloadHash) {
        return (recordSequence + "\n" + timestamp + "\n" + payloadHash).getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record PendingWrite(String line, long bytes) {
    }
}
