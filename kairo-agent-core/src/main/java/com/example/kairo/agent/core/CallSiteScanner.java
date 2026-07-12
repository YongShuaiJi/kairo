package com.example.kairo.agent.core;

import com.example.kairo.api.CallSiteIdentity;
import com.example.kairo.api.CallSiteSelector;
import com.example.kairo.api.InvokeOpcode;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.TargetMatchResult;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only scanner for {@code invoke*} instructions inside a caller method.
 *
 * <p>V1.3 uses this at publish time (never on the hot path) to enumerate the call
 * sites a call-site rule could attach to, to capture the stable identity
 * (caller + callee signature + opcode + occurrence index + surrounding-instruction
 * fingerprint) of the chosen site, and to re-resolve a recorded identity against
 * live bytecode so a recompilation that shifted the site is reported as
 * {@link TargetMatchResult.Status#DRIFTED} rather than silently weaving the wrong
 * instruction.
 *
 * <p>The scanner does not modify bytecode; it only reads the caller class bytes
 * (via the owning {@link ClassLoader}) and walks the target method with a plain
 * ASM visitor. Absolute bytecode offsets are deliberately not used as identity:
 * they are not stable across recompilation. The fingerprint is a window of
 * instruction opcodes around the invoke, which is stable as long as the
 * surrounding code is unchanged.
 */
public final class CallSiteScanner {

    /** Number of instruction opcodes captured on each side of the invoke for the fingerprint. */
    static final int FINGERPRINT_WINDOW = 4;

    /**
     * Enumerate every {@code invoke*} instruction in the caller method that matches the
     * given callee signature and opcode, in bytecode visit order. Each result carries its
     * 0-based occurrence index (among the matches) and a freshly captured fingerprint.
     */
    public List<CallSiteIdentity> scan(Class<?> callerClass, MethodSelector caller,
                                       String calleeOwner, String calleeName,
                                       String calleeDescriptor, InvokeOpcode opcode) {
        byte[] bytes = readClassBytes(callerClass);
        if (bytes == null) {
            return List.of();
        }
        String ownerInternal = calleeOwner.replace('.', '/');
        int opcodeInt = opcode.opcode();
        List<CallSiteIdentity> hits = new ArrayList<>();
        try {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (!name.equals(caller.methodName()) || !descriptor.equals(caller.methodDescriptor())) {
                        return null;
                    }
                    return new CollectingVisitor(ownerInternal, calleeName, calleeDescriptor, opcodeInt,
                            caller, hits);
                }
            }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        } catch (Exception ignored) {
            // unreadable or unparseable bytes -> no candidates
            return List.of();
        }
        return List.copyOf(hits);
    }

    /**
     * Resolve a recorded call-site selector against live bytecode. Returns
     * {@link TargetMatchResult.Status#MATCHED} with the freshly resolved identity when the
     * occurrence is present and the fingerprint is consistent (or was not previously
     * captured), {@link TargetMatchResult.Status#NOT_FOUND} when the occurrence is absent,
     * and {@link TargetMatchResult.Status#DRIFTED} when the occurrence is present but the
     * surrounding-instruction fingerprint changed.
     */
    public TargetMatchResult resolveCallSite(Class<?> callerClass, MethodSelector caller,
                                             CallSiteSelector recorded) {
        List<CallSiteIdentity> hits = scan(callerClass, caller, recorded.owner(), recorded.name(),
                recorded.descriptor(), recorded.opcode());
        if (recorded.occurrenceIndex() >= hits.size()) {
            return TargetMatchResult.notFound("occurrence " + recorded.occurrenceIndex()
                    + " not found among " + hits.size() + " matching invoke(s) for "
                    + recorded.opcode() + " " + recorded.owner() + "." + recorded.name() + recorded.descriptor());
        }
        CallSiteIdentity fresh = hits.get(recorded.occurrenceIndex());
        CallSiteIdentity recordedId = new CallSiteIdentity(caller, recorded);
        if (!recordedId.fingerprintMatches(fresh)) {
            return TargetMatchResult.drifted("surrounding-instruction fingerprint changed for "
                    + recorded, fresh);
        }
        return TargetMatchResult.matchedCallSite(fresh);
    }

    private static byte[] readClassBytes(Class<?> callerClass) {
        ClassLoader loader = callerClass.getClassLoader();
        String binaryName = callerClass.getName();
        if (loader != null) {
            try {
                ClassFileLocator.Resolution resolution =
                        ClassFileLocator.ForClassLoader.of(loader).locate(binaryName);
                if (resolution != null && resolution.isResolved()) {
                    return resolution.resolve();
                }
            } catch (Exception ignored) {
                // fall through to resource lookup
            }
        }
        String path = binaryName.replace('.', '/') + ".class";
        ClassLoader[] loaders = new ClassLoader[]{
                loader,
                Thread.currentThread().getContextClassLoader(),
                CallSiteScanner.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader cl : loaders) {
            if (cl == null) {
                continue;
            }
            try (var stream = cl.getResourceAsStream(path)) {
                if (stream != null) {
                    return stream.readAllBytes();
                }
            } catch (Exception ignored) {
                // try next loader
            }
        }
        return null;
    }

    /**
     * ASM visitor that records the opcode of every instruction in visit order and the
     * positions of matching {@code invoke*} instructions, then at {@code visitEnd} builds
     * the {@link CallSiteIdentity} list with occurrence indices and fingerprints.
     */
    private static final class CollectingVisitor extends MethodVisitor {
        private final String ownerInternal;
        private final String calleeName;
        private final String calleeDescriptor;
        private final int calleeOpcode;
        private final MethodSelector caller;
        private final List<CallSiteIdentity> hits;

        private final List<Integer> opcodes = new ArrayList<>();
        private final List<InvokeHit> invokes = new ArrayList<>();

        CollectingVisitor(String ownerInternal, String calleeName, String calleeDescriptor,
                          int calleeOpcode, MethodSelector caller, List<CallSiteIdentity> hits) {
            super(Opcodes.ASM9);
            this.ownerInternal = ownerInternal;
            this.calleeName = calleeName;
            this.calleeDescriptor = calleeDescriptor;
            this.calleeOpcode = calleeOpcode;
            this.caller = caller;
            this.hits = hits;
        }

        @Override
        public void visitInsn(int opcode) {
            record(opcode);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            record(opcode);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            record(opcode);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            record(opcode);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            record(opcode);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            int pos = opcodes.size();
            record(opcode);
            if (opcode == calleeOpcode
                    && owner.equals(ownerInternal)
                    && name.equals(calleeName)
                    && descriptor.equals(calleeDescriptor)) {
                invokes.add(new InvokeHit(pos));
            }
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, net.bytebuddy.jar.asm.Handle handle,
                                           Object... bootstrapMethodArguments) {
            record(Opcodes.INVOKEDYNAMIC);
        }

        @Override
        public void visitJumpInsn(int opcode, net.bytebuddy.jar.asm.Label label) {
            record(opcode);
        }

        @Override
        public void visitLdcInsn(Object value) {
            record(Opcodes.LDC);
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            record(Opcodes.IINC);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, net.bytebuddy.jar.asm.Label dflt, net.bytebuddy.jar.asm.Label... labels) {
            record(Opcodes.TABLESWITCH);
        }

        @Override
        public void visitLookupSwitchInsn(net.bytebuddy.jar.asm.Label dflt, int[] keys, net.bytebuddy.jar.asm.Label[] labels) {
            record(Opcodes.LOOKUPSWITCH);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int dims) {
            record(Opcodes.MULTIANEWARRAY);
        }

        private void record(int opcode) {
            opcodes.add(opcode);
        }

        @Override
        public void visitEnd() {
            for (int i = 0; i < invokes.size(); i++) {
                InvokeHit hit = invokes.get(i);
                CallSiteSelector selector = CallSiteSelector.builder()
                        .owner(ownerInternal.replace('/', '.'))
                        .name(calleeName)
                        .descriptor(calleeDescriptor)
                        .opcode(InvokeOpcode.fromOpcode(calleeOpcode))
                        .occurrenceIndex(i)
                        .fingerprint(fingerprint(hit.pos))
                        .build();
                hits.add(new CallSiteIdentity(caller, selector));
            }
        }

        private String fingerprint(int pos) {
            int from = Math.max(0, pos - FINGERPRINT_WINDOW);
            int to = Math.min(opcodes.size(), pos + FINGERPRINT_WINDOW + 1);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < to; i++) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(opcodes.get(i));
            }
            return sb.toString();
        }
    }

    /** Position (in the opcode list) of one matching invoke instruction. */
    private record InvokeHit(int pos) {
    }
}
