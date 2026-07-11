package com.example.kairo.agent.core.bytecode;

import com.example.kairo.api.bytecode.BytecodeDecompiler;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.DecompilationResult;
import com.example.kairo.api.bytecode.DecompilationStatus;

import net.bytebuddy.jar.asm.ClassReader;

import org.jetbrains.java.decompiler.api.Decompiler;
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;
import org.jetbrains.java.decompiler.main.extern.IContextSource;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * Default {@link BytecodeDecompiler} backed by the official Vineflower decompiler
 * ({@code org.vineflower:vineflower}). It decompiles a single target class purely from
 * the supplied {@code byte[]}: no temporary files are written, and no class loader,
 * file system or archive is touched. Vineflower's resource abstractions are satisfied
 * with in-memory implementations:
 * <ul>
 *   <li>{@link MemoryClassSource} (an {@link IContextSource}) serves the target bytes;</li>
 *   <li>a capturing {@link IContextSource.IOutputSink} collects the decompiled source;</li>
 *   <li>a no-op {@link IResultSaver} stands in for the (unused) sink target;</li>
 *   <li>a capturing {@link IFernflowerLogger} absorbs warnings/errors into diagnostics
 *       instead of writing to {@code System.out}/{@code System.err}.</li>
 * </ul>
 *
 * <p>The result is deliberately honest. The produced source is <em>approximate</em>:
 * decompilation reconstructs readable Java but may rename locals, synthesize casts or
 * omit details, so a diagnostic says so on every successful result. The structured
 * bytecode diff remains the authoritative comparison. A result whose status is not
 * {@link DecompilationStatus#SUCCESS} never carries source.
 *
 * <p>If the class name declared in the bytes does not match the supplied
 * {@link ClassIdentity}, decompilation fails rather than returning source for the wrong
 * class. Any throwable raised by Vineflower is converted to a {@link DecompilationStatus#FAILED}
 * result; this implementation never propagates an exception to the caller, so the
 * bounded {@link DecompilerService} that wraps it is never destabilised.
 */
public final class VineflowerBytecodeDecompiler implements BytecodeDecompiler {

    /** Stable short name reported via {@link DecompilationResult#decompilerName()}. */
    public static final String NAME = "vineflower";

    /**
     * Compile-time fallback when neither the filtered properties resource nor
     * Vineflower's manifest lookup yields a version. Kept in sync with the
     * {@code vineflower.version} Maven property by {@link #resolveVersion()}.
     */
    private static final String FALLBACK_VERSION = "1.12.0";

    private final String version;

    /**
     * Eagerly validates that Vineflower is on the classpath and captures its version.
     * A missing dependency therefore surfaces here (the {@code BytecodeDecompilers}
     * factory catches the resulting {@code NoClassDefFoundError} and falls back to the
     * unavailable decompiler) rather than on every {@link #decompile(ClassIdentity, byte[])}
     * call.
     */
    public VineflowerBytecodeDecompiler() {
        this.version = resolveVersion();
    }

    /** Vineflower implementation version (e.g. {@code "1.12.0"}), for diagnostics. */
    public String version() {
        return version;
    }

    /**
     * Resolve the Vineflower version. The build-filtered {@code decompiler.properties}
     * resource is preferred because it survives shading into {@code kairo-agent-core-modern}
     * (where Vineflower's own jar manifest is merged away and
     * {@code ConsoleDecompiler.version()} would otherwise return {@code <UNK>}). Vineflower's
     * manifest lookup is the fallback when the resource is absent (e.g. running from
     * unshaded classes), and a compile-time constant is the last resort.
     */
    private static String resolveVersion() {
        String filtered = readFilteredVersion();
        if (isUsableVersion(filtered)) {
            return filtered;
        }
        try {
            String manifest = ConsoleDecompiler.version();
            if (isUsableVersion(manifest) && !"<UNK>".equals(manifest)) {
                return manifest;
            }
        } catch (Throwable ignored) {
            // Vineflower present but version lookup failed; fall through to constant.
        }
        return FALLBACK_VERSION;
    }

    private static String readFilteredVersion() {
        try (InputStream in = VineflowerBytecodeDecompiler.class.getResourceAsStream("decompiler.properties")) {
            if (in == null) {
                return null;
            }
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("vineflower.version");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isUsableVersion(String value) {
        return value != null && !value.isBlank() && !value.startsWith("${");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DecompilationResult decompile(ClassIdentity classIdentity, byte[] bytes) {
        Objects.requireNonNull(classIdentity, "classIdentity");
        Objects.requireNonNull(bytes, "bytes");
        long started = System.currentTimeMillis();

        String internalName;
        try {
            internalName = new ClassReader(bytes).getClassName();
        } catch (RuntimeException e) {
            return failed("cannot read class name from bytes: " + describe(e), started);
        }
        String declaredBinary = internalName.replace('/', '.');
        if (!declaredBinary.equals(classIdentity.binaryClassName())) {
            return failed("class name mismatch: identity=" + classIdentity.binaryClassName()
                    + " but bytes declare " + declaredBinary, started);
        }

        MemoryClassSource source = new MemoryClassSource(internalName, bytes);
        try {
            Decompiler.builder()
                    .inputs(source)
                    .output(NoOpResultSaver.INSTANCE)
                    .logger(source.logger)
                    .build()
                    .decompile();
        } catch (Throwable t) {
            return failed("vineflower decompilation failed: " + describe(t), started);
        }

        String captured = source.capturedSource();
        if (captured == null || captured.isBlank()) {
            return failed("vineflower produced no source for " + declaredBinary
                    + source.logger.shortWarnings(), started);
        }
        List<String> diagnostics = new ArrayList<>();
        diagnostics.add("Decompiled with Vineflower " + version
                + "; source is approximate and may rename locals, synthesize casts or omit"
                + " details - the structured bytecode diff is authoritative.");
        diagnostics.addAll(source.logger.warnings);
        return new DecompilationResult(DecompilationStatus.SUCCESS, NAME, captured,
                diagnostics, System.currentTimeMillis() - started);
    }

    private DecompilationResult failed(String message, long started) {
        return new DecompilationResult(DecompilationStatus.FAILED, NAME, null,
                List.of(message), System.currentTimeMillis() - started);
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return (message == null || message.isBlank())
                ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + ": " + message;
    }

    /**
     * In-memory {@link IContextSource} that exposes exactly one class - the target -
     * from the supplied {@code byte[]}. No file, archive or class loader is involved.
     * The accompanying {@link CapturingLogger} collects decompiler diagnostics.
     */
    private static final class MemoryClassSource implements IContextSource {
        private final String internalName;
        private final byte[] bytes;
        private final CapturingLogger logger = new CapturingLogger();

        MemoryClassSource(String internalName, byte[] bytes) {
            this.internalName = internalName;
            this.bytes = bytes;
        }

        @Override
        public String getName() {
            return "kairo-memory";
        }

        @Override
        public Entries getEntries() {
            return new Entries(
                    List.of(Entry.atBase(internalName + ".class")),
                    List.of(),
                    List.of());
        }

        @Override
        public boolean hasClass(String name) {
            return internalName.equals(name) || (internalName + ".class").equals(name);
        }

        @Override
        public byte[] getClassBytes(String name) {
            return bytes;
        }

        @Override
        public InputStream getInputStream(String path) {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public IOutputSink createOutputSink(IResultSaver saver) {
            return new IOutputSink() {
                @Override
                public void begin() {
                    // no-op
                }

                @Override
                public void acceptClass(String qualifiedName, String entryName,
                                        String content, int[] mapping) {
                    if (content != null && !content.isBlank()
                            && internalName.equals(qualifiedName)) {
                        capture(content);
                    }
                }

                @Override
                public void acceptDirectory(String path) {
                    // no-op
                }

                @Override
                public void acceptOther(String path) {
                    // no-op
                }

                @Override
                public void close() {
                    // no-op
                }
            };
        }

        private volatile String captured;

        void capture(String content) {
            this.captured = content;
        }

        String capturedSource() {
            return captured;
        }
    }

    /**
     * A {@link IFernflowerLogger} that never writes to {@code System.out} or
     * {@code System.err}: warning and error messages are collected into a list so they
     * can surface as {@link DecompilationResult} diagnostics instead of polluting the
     * agent console.
     */
    private static final class CapturingLogger extends IFernflowerLogger {
        private final List<String> warnings = new ArrayList<>();

        @Override
        public void writeMessage(String message, Severity severity) {
            record(message, severity, null);
        }

        @Override
        public void writeMessage(String message, Severity severity, Throwable throwable) {
            record(message, severity, throwable);
        }

        private void record(String message, Severity severity, Throwable throwable) {
            if (severity == Severity.WARN || severity == Severity.ERROR) {
                String text = severity.name().toLowerCase(Locale.ROOT) + ": " + message;
                if (throwable != null) {
                    text += " (" + throwable.getClass().getSimpleName() + ")";
                }
                warnings.add(text);
            }
        }

        /** A single-line summary of captured warnings, for embedding in a failed result. */
        String shortWarnings() {
            return warnings.isEmpty() ? "" : "; warnings: " + String.join("; ", warnings);
        }
    }

    /**
     * No-op {@link IResultSaver}. Vineflower delivers decompiled source through the
     * {@link IContextSource.IOutputSink} returned by {@link MemoryClassSource}, so this
     * saver is never invoked with class content; it exists only to satisfy the builder.
     */
    private static final class NoOpResultSaver implements IResultSaver {
        static final NoOpResultSaver INSTANCE = new NoOpResultSaver();

        @Override
        public void saveFolder(String path) {
            // no-op
        }

        @Override
        public void copyFile(String from, String to, String entry) {
            // no-op
        }

        @Override
        public void saveClassFile(String pack, String name, String qualifiedName,
                                  String content, int[] mapping) {
            // no-op
        }

        @Override
        public void createArchive(String path, String archiveName,
                                  java.util.jar.Manifest manifest) {
            // no-op
        }

        @Override
        public void saveDirEntry(String path, String archiveName, String entryName) {
            // no-op
        }

        @Override
        public void copyEntry(String from, String to, String archive, String entry) {
            // no-op
        }

        @Override
        public void saveClassEntry(String path, String archiveName, String qualifiedName,
                                   String entryName, String content) {
            // no-op
        }

        @Override
        public void saveClassEntry(String path, String archiveName, String qualifiedName,
                                   String entryName, String content, int[] mapping) {
            // no-op
        }

        @Override
        public void closeArchive(String path, String archiveName) {
            // no-op
        }
    }
}
