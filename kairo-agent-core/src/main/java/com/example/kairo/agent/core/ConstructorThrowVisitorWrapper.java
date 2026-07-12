package com.example.kairo.agent.core;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.util.Set;

/**
 * Adds a constructor exception handler only after its first constructor call.
 * This is the verifier-safe boundary at which {@code this} is initialised.
 */
final class ConstructorThrowVisitorWrapper implements AsmVisitorWrapper {

    private final Set<MethodSignature> constructors;

    ConstructorThrowVisitorWrapper(Set<MethodSignature> constructors) {
        this.constructors = constructors;
    }

    @Override
    public int mergeReader(int flags) {
        return flags | ClassReader.EXPAND_FRAMES;
    }

    @Override
    public int mergeWriter(int flags) {
        // The inserted handler adds a control-flow edge. Let ASM derive valid
        // stack-map frames instead of attempting to preserve the old frames.
        return flags | ClassWriter.COMPUTE_FRAMES;
    }

    @Override
    public ClassVisitor wrap(TypeDescription type, ClassVisitor visitor, Implementation.Context context,
                             net.bytebuddy.pool.TypePool typePool,
                             net.bytebuddy.description.field.FieldList<FieldDescription.InDefinedShape> fields,
                             MethodList<?> methods, int writerFlags, int readerFlags) {
        return new ClassVisitor(Opcodes.ASM9, visitor) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"<init>".equals(name) || !matches(descriptor) || delegate == null) {
                    return delegate;
                }
                return new PostInitThrowableVisitor(delegate);
            }
        };
    }

    private boolean matches(String descriptor) {
        return constructors.stream().anyMatch(candidate -> candidate.methodDescriptor().equals(descriptor));
    }

    private static final class PostInitThrowableVisitor extends MethodVisitor {
        private final Label start = new Label();
        private final Label end = new Label();
        private final Label handler = new Label();
        private boolean initialised;

        private PostInitThrowableVisitor(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (!initialised && opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name)) {
                initialised = true;
                super.visitLabel(start);
            }
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            if (initialised) {
                super.visitLabel(end);
                super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
                super.visitLabel(handler);
                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "com/example/kairo/agent/core/ConstructorBridge", "exitThrow",
                        "(Ljava/lang/Throwable;)Ljava/lang/Throwable;", false);
                super.visitInsn(Opcodes.ATHROW);
                super.visitMaxs(Math.max(maxStack, 1), maxLocals);
                return;
            }
            super.visitMaxs(maxStack, maxLocals);
        }
    }
}
