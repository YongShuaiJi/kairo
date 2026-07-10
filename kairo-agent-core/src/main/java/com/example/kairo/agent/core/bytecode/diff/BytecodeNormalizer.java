package com.example.kairo.agent.core.bytecode.diff;

import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Reads class bytes with Byte Buddy's shaded ASM core visitor API and produces a
 * {@link NormalizedClass}. Byte Buddy's shaded ASM does not ship the {@code tree}
 * package, so normalization is done with the core {@code ClassVisitor} /
 * {@code MethodVisitor} API; the resulting model is equivalent for semantic
 * comparison.
 *
 * <p>Normalization choices (deliberate, to avoid false diffs):
 * <ul>
 *   <li>constant-pool indices are never exposed by the visitor API - only
 *       resolved owner/name/descriptor strings - so they cannot cause noise;</li>
 *   <li>stack-map frames are skipped ({@link ClassReader#SKIP_FRAMES}); they are
 *       a deterministic function of instructions and would create false diffs
 *       between {@code disableClassFormatChanges} patch output and full rewrites;</li>
 *   <li>line numbers, source file and local-variable names are skipped
 *       ({@link ClassReader#SKIP_DEBUG});</li>
 *   <li>{@code Label} identity is normalized to a per-method sequential index
 *       assigned in first-encounter order, so jump targets compare stably across
 *       independently-produced bytes.</li>
 * </ul>
 */
final class BytecodeNormalizer {

    static NormalizedClass normalize(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        NormalizingVisitor visitor = new NormalizingVisitor();
        new ClassReader(bytes).accept(visitor, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return visitor.build();
    }

    private BytecodeNormalizer() {
    }

    private static final class NormalizingVisitor extends ClassVisitor {

        private String name;
        private String superName;
        private String signature;
        private int access;
        private final List<String> interfaces = new ArrayList<>();
        private final List<String> annotations = new ArrayList<>();
        private final List<NormalizedClass.NormalizedField> fields = new ArrayList<>();
        private final List<NormalizedClass.NormalizedMethod> methods = new ArrayList<>();

        NormalizingVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.access = access;
            this.name = name;
            this.signature = signature;
            this.superName = superName;
            if (interfaces != null) {
                for (String iface : interfaces) {
                    this.interfaces.add(iface);
                }
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            annotations.add((visible ? "+" : "-") + descriptor);
            return null;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            fields.add(new NormalizedClass.NormalizedField(access, name, descriptor, signature,
                    value == null ? "null" : value.toString()));
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            List<String> throwsList = new ArrayList<>();
            if (exceptions != null) {
                for (String exc : exceptions) {
                    throwsList.add(exc);
                }
            }
            return new MethodNormalizer(access, name, descriptor, signature, throwsList, methods);
        }

        NormalizedClass build() {
            return new NormalizedClass(name, superName, signature, access,
                    interfaces, annotations, fields, methods);
        }
    }

    private static final class MethodNormalizer extends MethodVisitor {

        private final int access;
        private final String name;
        private final String desc;
        private final String signature;
        private final List<String> exceptions;
        private final List<String> annotations = new ArrayList<>();
        private final List<String> tryCatch = new ArrayList<>();
        private final List<String> instructions = new ArrayList<>();
        private final IdentityHashMap<Label, Integer> labelIds = new IdentityHashMap<>();
        private final List<NormalizedClass.NormalizedMethod> sink;

        MethodNormalizer(int access, String name, String desc, String signature,
                         List<String> exceptions, List<NormalizedClass.NormalizedMethod> sink) {
            super(Opcodes.ASM9);
            this.access = access;
            this.name = name;
            this.desc = desc;
            this.signature = signature;
            this.exceptions = exceptions;
            this.sink = sink;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            annotations.add((visible ? "+" : "-") + descriptor);
            return null;
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            tryCatch.add("TRYCATCH " + label(start) + ".." + label(end)
                    + " -> " + label(handler) + " : " + (type == null ? "any" : type));
        }

        @Override
        public void visitLabel(Label label) {
            instructions.add(label(label) + ":");
        }

        @Override
        public void visitInsn(int opcode) {
            instructions.add(opName(opcode));
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            instructions.add(opName(opcode) + " " + operand);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            instructions.add(opName(opcode) + " " + var);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            instructions.add(opName(opcode) + " " + type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            instructions.add(opName(opcode) + " " + owner + "." + name + " " + descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            instructions.add(opName(opcode) + " " + owner + "." + name + descriptor
                    + (isInterface ? " itf" : ""));
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle handle, Object... bootstrapArgs) {
            instructions.add("INVOKEDYNAMIC " + name + descriptor + " bsm=" + handle + " args=" + renderArgs(bootstrapArgs));
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            instructions.add(opName(opcode) + " " + label(label));
        }

        @Override
        public void visitLdcInsn(Object value) {
            String rendered;
            if (value instanceof Type t) {
                rendered = "type:" + t;
            } else if (value instanceof Handle h) {
                rendered = "handle:" + h;
            } else if (value instanceof String s) {
                rendered = "str:" + s;
            } else {
                rendered = value.getClass().getSimpleName() + ":" + value;
            }
            instructions.add("LDC " + rendered);
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            instructions.add("IINC " + var + " " + increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            StringBuilder sb = new StringBuilder("TABLESWITCH ").append(min).append("..").append(max)
                    .append(" dflt=").append(label(dflt)).append(" [");
            for (int i = 0; i < labels.length; i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(label(labels[i]));
            }
            sb.append("]");
            instructions.add(sb.toString());
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            StringBuilder sb = new StringBuilder("LOOKUPSWITCH dflt=").append(label(dflt)).append(" [");
            for (int i = 0; i < keys.length; i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(keys[i]).append("=").append(label(labels[i]));
            }
            sb.append("]");
            instructions.add(sb.toString());
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            instructions.add("MULTIANEWARRAY " + descriptor + " " + numDimensions);
        }

        @Override
        public void visitEnd() {
            sink.add(new NormalizedClass.NormalizedMethod(access, name, desc, signature,
                    exceptions, annotations, tryCatch, instructions));
        }

        private String label(Label label) {
            Integer id = labelIds.get(label);
            if (id == null) {
                id = labelIds.size();
                labelIds.put(label, id);
            }
            return "L" + id;
        }

        private static String renderArgs(Object[] args) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    sb.append(",");
                }
                Object a = args[i];
                sb.append(a == null ? "null" : a.toString());
            }
            return sb.append("]").toString();
        }
    }

    /**
     * Human-readable JVM opcode name. Covers every standard opcode; unknown
     * values degrade to a hex form so the token stays stable and unique.
     */
    static String opName(int opcode) {
        switch (opcode) {
            case Opcodes.NOP: return "NOP";
            case Opcodes.ACONST_NULL: return "ACONST_NULL";
            case Opcodes.ICONST_M1: return "ICONST_M1";
            case Opcodes.ICONST_0: return "ICONST_0";
            case Opcodes.ICONST_1: return "ICONST_1";
            case Opcodes.ICONST_2: return "ICONST_2";
            case Opcodes.ICONST_3: return "ICONST_3";
            case Opcodes.ICONST_4: return "ICONST_4";
            case Opcodes.ICONST_5: return "ICONST_5";
            case Opcodes.LCONST_0: return "LCONST_0";
            case Opcodes.LCONST_1: return "LCONST_1";
            case Opcodes.FCONST_0: return "FCONST_0";
            case Opcodes.FCONST_1: return "FCONST_1";
            case Opcodes.FCONST_2: return "FCONST_2";
            case Opcodes.DCONST_0: return "DCONST_0";
            case Opcodes.DCONST_1: return "DCONST_1";
            case Opcodes.IALOAD: return "IALOAD";
            case Opcodes.LALOAD: return "LALOAD";
            case Opcodes.FALOAD: return "FALOAD";
            case Opcodes.DALOAD: return "DALOAD";
            case Opcodes.AALOAD: return "AALOAD";
            case Opcodes.BALOAD: return "BALOAD";
            case Opcodes.CALOAD: return "CALOAD";
            case Opcodes.SALOAD: return "SALOAD";
            case Opcodes.IASTORE: return "IASTORE";
            case Opcodes.LASTORE: return "LASTORE";
            case Opcodes.FASTORE: return "FASTORE";
            case Opcodes.DASTORE: return "DASTORE";
            case Opcodes.AASTORE: return "AASTORE";
            case Opcodes.BASTORE: return "BASTORE";
            case Opcodes.CASTORE: return "CASTORE";
            case Opcodes.SASTORE: return "SASTORE";
            case Opcodes.POP: return "POP";
            case Opcodes.POP2: return "POP2";
            case Opcodes.DUP: return "DUP";
            case Opcodes.DUP_X1: return "DUP_X1";
            case Opcodes.DUP_X2: return "DUP_X2";
            case Opcodes.DUP2: return "DUP2";
            case Opcodes.DUP2_X1: return "DUP2_X1";
            case Opcodes.DUP2_X2: return "DUP2_X2";
            case Opcodes.SWAP: return "SWAP";
            case Opcodes.IADD: return "IADD";
            case Opcodes.LADD: return "LADD";
            case Opcodes.FADD: return "FADD";
            case Opcodes.DADD: return "DADD";
            case Opcodes.ISUB: return "ISUB";
            case Opcodes.LSUB: return "LSUB";
            case Opcodes.FSUB: return "FSUB";
            case Opcodes.DSUB: return "DSUB";
            case Opcodes.IMUL: return "IMUL";
            case Opcodes.LMUL: return "LMUL";
            case Opcodes.FMUL: return "FMUL";
            case Opcodes.DMUL: return "DMUL";
            case Opcodes.IDIV: return "IDIV";
            case Opcodes.LDIV: return "LDIV";
            case Opcodes.FDIV: return "FDIV";
            case Opcodes.DDIV: return "DDIV";
            case Opcodes.IREM: return "IREM";
            case Opcodes.LREM: return "LREM";
            case Opcodes.FREM: return "FREM";
            case Opcodes.DREM: return "DREM";
            case Opcodes.INEG: return "INEG";
            case Opcodes.LNEG: return "LNEG";
            case Opcodes.FNEG: return "FNEG";
            case Opcodes.DNEG: return "DNEG";
            case Opcodes.ISHL: return "ISHL";
            case Opcodes.LSHL: return "LSHL";
            case Opcodes.ISHR: return "ISHR";
            case Opcodes.LSHR: return "LSHR";
            case Opcodes.IUSHR: return "IUSHR";
            case Opcodes.LUSHR: return "LUSHR";
            case Opcodes.IAND: return "IAND";
            case Opcodes.LAND: return "LAND";
            case Opcodes.IOR: return "IOR";
            case Opcodes.LOR: return "LOR";
            case Opcodes.IXOR: return "IXOR";
            case Opcodes.LXOR: return "LXOR";
            case Opcodes.I2L: return "I2L";
            case Opcodes.I2F: return "I2F";
            case Opcodes.I2D: return "I2D";
            case Opcodes.L2I: return "L2I";
            case Opcodes.L2F: return "L2F";
            case Opcodes.L2D: return "L2D";
            case Opcodes.F2I: return "F2I";
            case Opcodes.F2L: return "F2L";
            case Opcodes.F2D: return "F2D";
            case Opcodes.D2I: return "D2I";
            case Opcodes.D2L: return "D2L";
            case Opcodes.D2F: return "D2F";
            case Opcodes.I2B: return "I2B";
            case Opcodes.I2C: return "I2C";
            case Opcodes.I2S: return "I2S";
            case Opcodes.LCMP: return "LCMP";
            case Opcodes.FCMPL: return "FCMPL";
            case Opcodes.FCMPG: return "FCMPG";
            case Opcodes.DCMPL: return "DCMPL";
            case Opcodes.DCMPG: return "DCMPG";
            case Opcodes.IRETURN: return "IRETURN";
            case Opcodes.LRETURN: return "LRETURN";
            case Opcodes.FRETURN: return "FRETURN";
            case Opcodes.DRETURN: return "DRETURN";
            case Opcodes.ARETURN: return "ARETURN";
            case Opcodes.RETURN: return "RETURN";
            case Opcodes.ARRAYLENGTH: return "ARRAYLENGTH";
            case Opcodes.ATHROW: return "ATHROW";
            case Opcodes.MONITORENTER: return "MONITORENTER";
            case Opcodes.MONITOREXIT: return "MONITOREXIT";
            case Opcodes.ILOAD: return "ILOAD";
            case Opcodes.LLOAD: return "LLOAD";
            case Opcodes.FLOAD: return "FLOAD";
            case Opcodes.DLOAD: return "DLOAD";
            case Opcodes.ALOAD: return "ALOAD";
            case Opcodes.ISTORE: return "ISTORE";
            case Opcodes.LSTORE: return "LSTORE";
            case Opcodes.FSTORE: return "FSTORE";
            case Opcodes.DSTORE: return "DSTORE";
            case Opcodes.ASTORE: return "ASTORE";
            case Opcodes.RET: return "RET";
            case Opcodes.BIPUSH: return "BIPUSH";
            case Opcodes.SIPUSH: return "SIPUSH";
            case Opcodes.NEWARRAY: return "NEWARRAY";
            case Opcodes.NEW: return "NEW";
            case Opcodes.ANEWARRAY: return "ANEWARRAY";
            case Opcodes.CHECKCAST: return "CHECKCAST";
            case Opcodes.INSTANCEOF: return "INSTANCEOF";
            case Opcodes.GETSTATIC: return "GETSTATIC";
            case Opcodes.PUTSTATIC: return "PUTSTATIC";
            case Opcodes.GETFIELD: return "GETFIELD";
            case Opcodes.PUTFIELD: return "PUTFIELD";
            case Opcodes.INVOKEVIRTUAL: return "INVOKEVIRTUAL";
            case Opcodes.INVOKESPECIAL: return "INVOKESPECIAL";
            case Opcodes.INVOKESTATIC: return "INVOKESTATIC";
            case Opcodes.INVOKEINTERFACE: return "INVOKEINTERFACE";
            case Opcodes.IFEQ: return "IFEQ";
            case Opcodes.IFNE: return "IFNE";
            case Opcodes.IFLT: return "IFLT";
            case Opcodes.IFGE: return "IFGE";
            case Opcodes.IFGT: return "IFGT";
            case Opcodes.IFLE: return "IFLE";
            case Opcodes.IF_ICMPEQ: return "IF_ICMPEQ";
            case Opcodes.IF_ICMPNE: return "IF_ICMPNE";
            case Opcodes.IF_ICMPLT: return "IF_ICMPLT";
            case Opcodes.IF_ICMPGE: return "IF_ICMPGE";
            case Opcodes.IF_ICMPGT: return "IF_ICMPGT";
            case Opcodes.IF_ICMPLE: return "IF_ICMPLE";
            case Opcodes.IF_ACMPEQ: return "IF_ACMPEQ";
            case Opcodes.IF_ACMPNE: return "IF_ACMPNE";
            case Opcodes.GOTO: return "GOTO";
            case Opcodes.JSR: return "JSR";
            case Opcodes.IFNULL: return "IFNULL";
            case Opcodes.IFNONNULL: return "IFNONNULL";
            default: return "OP_0x" + Integer.toHexString(opcode);
        }
    }
}
