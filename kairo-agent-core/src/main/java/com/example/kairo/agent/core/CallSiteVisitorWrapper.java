package com.example.kairo.agent.core;

import com.example.kairo.api.EnhancementTarget;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single V1.3 call-site transformation implementation. Weaves a straight-line
 * shim around the Nth matching {@code invoke*} instruction in a caller method that
 * delegates to {@link CallSiteBridge}; the original invoke is performed
 * reflectively inside the helper so the generated shim has no branches or
 * exception handlers and therefore needs no stack-map frames (the method's
 * existing frames stay valid, and {@code disableClassFormatChanges} is honoured).
 *
 * <p>The shim saves the invoke operands to new locals allocated beyond the
 * method's current {@code maxLocals} (pre-scanned from the class bytes), builds an
 * {@code Object[]} argument array, calls {@link CallSiteBridge#invoke} and unboxes
 * the result. New locals are reported via a bumped {@code visitMaxs}.
 *
 * <p>One implementation only &mdash; there is no competing call-site transformer.
 * Occurrence selection, owner/name/descriptor/opcode matching and the surrounding
 * instruction fingerprint (drift) are resolved by the call-site scanner at publish
 * time; this visitor only weaves the already-resolved selectors.
 */
public final class CallSiteVisitorWrapper implements AsmVisitorWrapper {

    private final Map<String, List<CallSiteSpec>> specsByMethod;
    private final ClassLoader classLoader;

    private CallSiteVisitorWrapper(Map<String, List<CallSiteSpec>> specsByMethod, ClassLoader classLoader) {
        this.specsByMethod = specsByMethod;
        this.classLoader = classLoader;
    }

    /**
     * Build a visitor for the given call-site targets. Only targets whose caller
     * method belongs to the class being transformed are woven; the rest are
     * ignored (they belong to other classes). The {@code classLoader} is the
     * loader that owns the class being transformed; it is used only to read the
     * original class bytes for the maxLocals pre-scan and is never retained
     * after the transformation pass.
     */
    public static AsmVisitorWrapper forTargets(Set<EnhancementTarget> targets, ClassLoader classLoader) {
        Map<String, List<CallSiteSpec>> byMethod = new LinkedHashMap<>();
        for (EnhancementTarget target : targets) {
            if (!target.location().isCallSiteLocation() || target.callSiteSelector() == null) {
                continue;
            }
            String key = methodKey(target.method().methodName(), target.method().methodDescriptor());
            byMethod.computeIfAbsent(key, k -> new ArrayList<>()).add(new CallSiteSpec(
                    target.method().methodName(),
                    target.method().methodDescriptor(),
                    toBinary(target.callSiteSelector().owner()),
                    target.callSiteSelector().name(),
                    target.callSiteSelector().descriptor(),
                    target.callSiteSelector().opcode().opcode(),
                    target.callSiteSelector().occurrenceIndex()));
        }
        return new CallSiteVisitorWrapper(byMethod, classLoader);
    }

    private static String toBinary(String owner) {
        return owner == null ? null : owner.replace('/', '.');
    }

    private static String methodKey(String name, String descriptor) {
        return name + descriptor;
    }

    @Override
    public int mergeReader(int flags) {
        return flags;
    }

    @Override
    public int mergeWriter(int flags) {
        return flags;
    }

    @Override
    public ClassVisitor wrap(TypeDescription typeDescription, ClassVisitor classVisitor,
                             Implementation.Context context,
                             net.bytebuddy.pool.TypePool typePool,
                             net.bytebuddy.description.field.FieldList<net.bytebuddy.description.field.FieldDescription.InDefinedShape> fields,
                             net.bytebuddy.description.method.MethodList<?> methods,
                             int writerFlags, int readerFlags) {
        if (specsByMethod.isEmpty()) {
            return classVisitor;
        }
        String internalName = typeDescription.getInternalName();
        String binaryName = typeDescription.getName();
        Map<String, Integer> maxLocalsByMethod = preScanMaxLocals(binaryName);
        return new ClassVisitor(Opcodes.ASM9, classVisitor) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                List<CallSiteSpec> specs = specsByMethod.get(methodKey(name, descriptor));
                if (specs == null || specs.isEmpty() || delegate == null) {
                    return delegate;
                }
                Integer scanned = maxLocalsByMethod.get(methodKey(name, descriptor));
                int firstLocal = (access & Opcodes.ACC_STATIC) != 0
                        ? Type.getArgumentTypes(descriptor).length
                        : Type.getArgumentTypes(descriptor).length + 1;
                int base = scanned != null ? scanned : (firstLocal + 64);
                return new CallSiteMethodVisitor(Opcodes.ASM9, delegate, access, name, descriptor,
                        specs, base, internalName, binaryName);
            }
        };
    }

    private Map<String, Integer> preScanMaxLocals(String binaryName) {
        Map<String, Integer> maxLocalsByMethod = new LinkedHashMap<>();
        byte[] bytes = readClassBytes(binaryName);
        if (bytes == null) {
            return maxLocalsByMethod;
        }
        try {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (!specsByMethod.containsKey(methodKey(name, descriptor))) {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            maxLocalsByMethod.put(methodKey(name, descriptor), maxLocals);
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        } catch (Exception ignored) {
            // pre-scan failure -> caller falls back to conservative base
        }
        return maxLocalsByMethod;
    }

    /**
     * Read the original class bytes for the maxLocals pre-scan. The class is
     * already loaded (it is being retransformed), so the loader that owns it can
     * re-serve its bytes; the context/agent/system loaders are tried as fallbacks
     * so the read-only preview path (which has no owning loader) also works for
     * classes on the classpath.
     */
    private byte[] readClassBytes(String binaryName) {
        if (classLoader != null) {
            try {
                ClassFileLocator.Resolution resolution =
                        ClassFileLocator.ForClassLoader.of(classLoader).locate(binaryName);
                if (resolution != null && resolution.isResolved()) {
                    return resolution.resolve();
                }
            } catch (Exception ignored) {
                // fall through to resource fallback
            }
        }
        return readResource(binaryName);
    }

    private byte[] readResource(String binaryName) {
        String path = binaryName.replace('.', '/') + ".class";
        ClassLoader[] loaders = new ClassLoader[]{
                classLoader,
                Thread.currentThread().getContextClassLoader(),
                CallSiteVisitorWrapper.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader loader : loaders) {
            if (loader == null) {
                continue;
            }
            try (var stream = loader.getResourceAsStream(path)) {
                if (stream != null) {
                    return stream.readAllBytes();
                }
            } catch (Exception ignored) {
                // try next loader
            }
        }
        return null;
    }

    /** Resolved call-site specification baked into the generated shim. */
    private record CallSiteSpec(String callerName, String callerDescriptor, String owner,
                                String name, String descriptor, int opcode, int occurrence) {
    }

    /**
     * Method visitor that replaces the targeted invoke instruction with the
     * {@link CallSiteBridge} shim. The shim is straight-line (no branches, no
     * handlers), so no stack-map frames are introduced; new locals are allocated
     * beyond the method's existing maxLocals and reported via {@link #visitMaxs}.
     */
    private static final class CallSiteMethodVisitor extends MethodVisitor {
        private final String callerInternalName;
        private final String callerBinaryName;
        private final String callerName;
        private final String callerDescriptor;
        private final List<CallSiteSpec> specs;
        private final int baseLocal;
        private final Map<String, Integer> occurrenceCounts = new LinkedHashMap<>();
        private int maxNewLocal;

        CallSiteMethodVisitor(int api, MethodVisitor delegate, int access, String name, String descriptor,
                              List<CallSiteSpec> specs, int baseLocal,
                              String callerInternalName, String callerBinaryName) {
            super(api, delegate);
            this.callerName = name;
            this.callerDescriptor = descriptor;
            this.specs = specs;
            this.baseLocal = baseLocal;
            this.callerInternalName = callerInternalName;
            this.callerBinaryName = callerBinaryName;
            this.maxNewLocal = baseLocal;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            String ownerBinary = owner.replace('/', '.');
            String key = ownerBinary + "#" + name + descriptor + "@" + opcode;
            // 0-based occurrence among matching invokes, in visit order; matches the
            // CallSiteSelector.occurrenceIndex produced by the scanner.
            int occurrence = occurrenceCounts.getOrDefault(key, 0);
            occurrenceCounts.put(key, occurrence + 1);
            CallSiteSpec spec = match(ownerBinary, name, descriptor, opcode, occurrence);
            if (spec == null) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                return;
            }
            weaveShim(spec, opcode, owner, name, descriptor);
        }

        private CallSiteSpec match(String ownerBinary, String name, String descriptor, int opcode, int occurrence) {
            for (CallSiteSpec spec : specs) {
                if (spec.owner().equals(ownerBinary)
                        && spec.name().equals(name)
                        && spec.descriptor().equals(descriptor)
                        && spec.opcode() == opcode
                        && spec.occurrence() == occurrence) {
                    return spec;
                }
            }
            return null;
        }

        private void weaveShim(CallSiteSpec spec, int opcode, String owner, String name, String descriptor) {
            boolean isStatic = opcode == Opcodes.INVOKESTATIC;
            Type[] argTypes = Type.getArgumentTypes(descriptor);
            Type returnType = Type.getReturnType(descriptor);
            int[] argLocals = new int[argTypes.length];
            int cursor = baseLocal;
            if (!isStatic) {
                cursor++; // receiver local
            }
            for (int i = 0; i < argTypes.length; i++) {
                argLocals[i] = cursor;
                cursor += argTypes[i].getSize();
            }
            int receiverLocal = baseLocal;
            int arrLocal = cursor;
            int nextLocal = arrLocal + 1;
            if (nextLocal > maxNewLocal) {
                maxNewLocal = nextLocal;
            }

            // Stack: [receiver?, a1, ..., an]. Save in reverse order.
            for (int i = argTypes.length - 1; i >= 0; i--) {
                super.visitVarInsn(storeOp(argTypes[i]), argLocals[i]);
            }
            if (!isStatic) {
                super.visitVarInsn(Opcodes.ASTORE, receiverLocal);
            }

            // Build Object[] args array (boxed).
            super.visitIntInsn(Opcodes.BIPUSH, argTypes.length);
            super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            super.visitVarInsn(Opcodes.ASTORE, arrLocal);
            for (int i = 0; i < argTypes.length; i++) {
                super.visitVarInsn(Opcodes.ALOAD, arrLocal);
                pushInt(i);
                super.visitVarInsn(loadOp(argTypes[i]), argLocals[i]);
                box(argTypes[i]);
                super.visitInsn(Opcodes.AASTORE);
            }

            // Call CallSiteBridge.invoke(...).
            super.visitLdcInsn(callerBinaryName);
            super.visitLdcInsn(callerName);
            super.visitLdcInsn(callerDescriptor);
            super.visitLdcInsn(Type.getObjectType(callerInternalName));
            super.visitLdcInsn(spec.owner());
            super.visitLdcInsn(spec.name());
            super.visitLdcInsn(descriptor);
            pushInt(opcode);
            pushInt(spec.occurrence());
            if (isStatic) {
                super.visitInsn(Opcodes.ACONST_NULL);
            } else {
                super.visitVarInsn(Opcodes.ALOAD, receiverLocal);
            }
            super.visitVarInsn(Opcodes.ALOAD, arrLocal);
            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/example/kairo/agent/core/CallSiteBridge", "invoke",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Class;"
                            + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II"
                            + "Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);

            // Unbox the result to the callee return type.
            unboxResult(returnType);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(Math.max(maxStack, 16), Math.max(maxLocals, maxNewLocal));
        }

        private void pushInt(int v) {
            if (v == -1) {
                super.visitInsn(Opcodes.ICONST_M1);
            } else if (v >= 0 && v <= 5) {
                super.visitInsn(Opcodes.ICONST_0 + v);
            } else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
                super.visitIntInsn(Opcodes.BIPUSH, v);
            } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
                super.visitIntInsn(Opcodes.SIPUSH, v);
            } else {
                super.visitLdcInsn(v);
            }
        }

        private static int storeOp(Type t) {
            switch (t.getSort()) {
                case Type.INT:
                case Type.SHORT:
                case Type.BYTE:
                case Type.CHAR:
                case Type.BOOLEAN:
                    return Opcodes.ISTORE;
                case Type.FLOAT:
                    return Opcodes.FSTORE;
                case Type.LONG:
                    return Opcodes.LSTORE;
                case Type.DOUBLE:
                    return Opcodes.DSTORE;
                default:
                    return Opcodes.ASTORE;
            }
        }

        private static int loadOp(Type t) {
            switch (t.getSort()) {
                case Type.INT:
                case Type.SHORT:
                case Type.BYTE:
                case Type.CHAR:
                case Type.BOOLEAN:
                    return Opcodes.ILOAD;
                case Type.FLOAT:
                    return Opcodes.FLOAD;
                case Type.LONG:
                    return Opcodes.LLOAD;
                case Type.DOUBLE:
                    return Opcodes.DLOAD;
                default:
                    return Opcodes.ALOAD;
            }
        }

        private void box(Type t) {
            switch (t.getSort()) {
                case Type.INT:
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                            "(I)Ljava/lang/Integer;", false);
                    break;
                case Type.BOOLEAN:
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf",
                            "(Z)Ljava/lang/Boolean;", false);
                    break;
                case Type.BYTE:
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf",
                            "(B)Ljava/lang/Byte;", false);
                    break;
                case Type.CHAR:
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf",
                            "(C)Ljava/lang/Character;", false);
                    break;
                case Type.SHORT:
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf",
                            "(S)Ljava/lang/Short;", false);
                    break;
                case Type.LONG:
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
                            "(J)Ljava/lang/Long;", false);
                    break;
                case Type.FLOAT:
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf",
                            "(F)Ljava/lang/Float;", false);
                    break;
                case Type.DOUBLE:
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf",
                            "(D)Ljava/lang/Double;", false);
                    break;
                default:
                    // reference or void: already an Object
                    break;
            }
        }

        private void unboxResult(Type returnType) {
            switch (returnType.getSort()) {
                case Type.VOID:
                    super.visitInsn(Opcodes.POP);
                    break;
                case Type.INT:
                    super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Integer");
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
                    break;
                case Type.BOOLEAN:
                    super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                    break;
                case Type.BYTE:
                    super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Byte");
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false);
                    break;
                case Type.CHAR:
                    super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Character");
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
                    break;
                case Type.SHORT:
                    super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Short");
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false);
                    break;
                case Type.LONG:
                    super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Long");
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false);
                    break;
                case Type.FLOAT:
                    super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Float");
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false);
                    break;
                case Type.DOUBLE:
                    super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Double");
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false);
                    break;
                case Type.ARRAY:
                    super.visitTypeInsn(Opcodes.CHECKCAST, returnType.getDescriptor());
                    break;
                default:
                    super.visitTypeInsn(Opcodes.CHECKCAST, returnType.getInternalName());
                    break;
            }
        }
    }

    /** Unused opcode guard removed; the visitor emits resolved opcodes directly. */
}
