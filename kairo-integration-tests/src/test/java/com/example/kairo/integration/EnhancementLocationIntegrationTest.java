package com.example.kairo.integration;

import com.example.demo.v13.EnhancementFixtures.Base;
import com.example.demo.v13.EnhancementFixtures.Chained;
import com.example.demo.v13.EnhancementFixtures.CallSiteSamples;
import com.example.demo.v13.EnhancementFixtures.DefaultInterfaceImpl;
import com.example.demo.v13.EnhancementFixtures.Derived;
import com.example.demo.v13.EnhancementFixtures.DriftCallee;
import com.example.demo.v13.EnhancementFixtures.DriftV1;
import com.example.demo.v13.EnhancementFixtures.DriftV2;
import com.example.demo.v13.EnhancementFixtures.GreeterImpl;
import com.example.demo.v13.EnhancementFixtures.MethodKinds;
import com.example.demo.v13.EnhancementFixtures.NativeHolder;
import com.example.demo.v13.EnhancementFixtures.SuperBase;
import com.example.demo.v13.EnhancementFixtures.SuperCaller;
import com.example.demo.v13.EnhancementFixtures.ThrowingCtor;
import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.agent.core.bytecode.BytecodeHash;
import com.example.kairo.agent.core.bytecode.ClassIdentities;
import com.example.kairo.api.CallSiteIdentity;
import com.example.kairo.api.CallSiteSelector;
import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.InvokeOpcode;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.MockRule;
import com.example.kairo.api.TargetMatchResult;
import com.example.kairo.api.bytecode.BytecodeDiffResult;
import com.example.kairo.api.bytecode.BytecodeSnapshotKind;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.core.MethodDescriptor;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V1.3 enhancement-location acceptance on a real JVM. Covers the unified location
 * model end-to-end: method FINALLY, constructor after-super/return/throw with
 * {@code this()}/{@code super()} safety, call-site before/return/throw around a
 * single invoke instruction, occurrence selection among repeated callees, drift
 * rejection, native/abstract early rejection, V1.0 rule no-migration compatibility,
 * and per-location unload restoring the original bytecode.
 */
class EnhancementLocationIntegrationTest {

    private AgentRuntime runtime;
    private Instrumentation instrumentation;

    @BeforeEach
    void setUp() {
        instrumentation = ByteBuddyAgent.install();
        runtime = new AgentRuntime(instrumentation);
        runtime.start();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    // -------------------------------------------------------- method locations

    @Test
    void finallyObservesOutcomeWithoutMutatingIt() throws Exception {
        Method method = MethodKinds.class.getMethod("valueMethod", int.class);
        runtime.publish(method, rule("finally", method, EnhancementLocation.METHOD_FINALLY, """
                log.info('finally saw ' + result)
                return mock.proceed()
                """), "test");
        // FINALLY is observe-only: the original value still flows through unchanged.
        assertThat(new MethodKinds().valueMethod(7)).isEqualTo("value-7");
    }

    @Test
    void methodThrowLocationReplacesException() throws Exception {
        Method method = CallSiteSamples.class.getMethod("boom");
        runtime.publish(method, rule("method-throw", method, EnhancementLocation.METHOD_THROW, """
                return mock.throwException('java.lang.IllegalStateException', 'method-throw-replaced')
                """), "test");
        // METHOD_THROW fires when the body throws; the rule replaces the throwable.
        assertThatThrownBy(() -> new CallSiteSamples().boom())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("method-throw-replaced");
    }

    @Test
    void methodKindsEnhanceAcrossStaticPrivateFinalSynchronizedDefaultInterface() throws Exception {
        Method staticMethod = MethodKinds.class.getMethod("staticTarget", String.class);
        runtime.publish(staticMethod, rule("static", staticMethod, EnhancementLocation.METHOD_RETURN, """
                return mock.returnValue('STATIC-MOCKED')
                """), "test");
        assertThat(MethodKinds.staticTarget("x")).isEqualTo("STATIC-MOCKED");

        Method valueMethod = MethodKinds.class.getMethod("valueMethod", int.class);
        runtime.publish(valueMethod, rule("value", valueMethod, EnhancementLocation.METHOD_RETURN, """
                return mock.returnValue('VALUE-MOCKED')
                """), "test");
        assertThat(new MethodKinds().valueMethod(3)).isEqualTo("VALUE-MOCKED");

        Method voidMethod = MethodKinds.class.getMethod("voidMethod", String.class);
        runtime.publish(voidMethod, rule("void", voidMethod, EnhancementLocation.METHOD_ENTER, """
                return mock.proceed(['void-mocked'] as Object[])
                """), "test");
        new MethodKinds().voidMethod("real");
        assertThat(MethodKinds.lastVoidArg).isEqualTo("void-mocked");

        Method finalMethod = MethodKinds.class.getMethod("finalMethod", String.class);
        runtime.publish(finalMethod, rule("final", finalMethod, EnhancementLocation.METHOD_RETURN, """
                return mock.returnValue('FINAL-MOCKED')
                """), "test");
        assertThat(new MethodKinds().finalMethod("x")).isEqualTo("FINAL-MOCKED");

        Method syncMethod = MethodKinds.class.getMethod("synchronizedMethod", String.class);
        runtime.publish(syncMethod, rule("sync", syncMethod, EnhancementLocation.METHOD_RETURN, """
                return mock.returnValue('SYNC-MOCKED')
                """), "test");
        assertThat(new MethodKinds().synchronizedMethod("x")).isEqualTo("SYNC-MOCKED");

        Method defaultMethod = DefaultInterfaceImpl.class.getMethod("defaultMethod", String.class);
        runtime.publish(defaultMethod, rule("default", defaultMethod, EnhancementLocation.METHOD_RETURN, """
                return mock.returnValue('DEFAULT-MOCKED')
                """), "test");
        assertThat(new DefaultInterfaceImpl().defaultMethod("x")).isEqualTo("DEFAULT-MOCKED");
    }

    // -------------------------------------------------------- constructor locations

    @Test
    void constructorAfterSuperObserveOnlyKeepsObjectInitialized() throws Exception {
        Constructor<?> ctor = Derived.class.getDeclaredConstructor();
        runtime.publishConstructor(ctor, constructorRule("ctor-after-super", ctor,
                EnhancementLocation.CONSTRUCTOR_AFTER_SUPER, """
                log.info('constructor after super observed')
                return mock.proceed()
                """), "test");
        // The object is fully constructed; the observe-only enter cannot short-circuit it.
        Derived derived = new Derived();
        assertThat(derived.tag).isEqualTo("derived");
    }

    @Test
    void chainedThisConstructorIsEnhancedSafely() throws Exception {
        Constructor<?> noArg = Chained.class.getDeclaredConstructor();
        runtime.publishConstructor(noArg, constructorRule("chain-noarg", noArg,
                EnhancementLocation.CONSTRUCTOR_AFTER_SUPER, """
                return mock.proceed()
                """), "test");
        Constructor<?> stringCtor = Chained.class.getDeclaredConstructor(String.class);
        runtime.publishConstructor(stringCtor, constructorRule("chain-string", stringCtor,
                EnhancementLocation.CONSTRUCTOR_AFTER_SUPER, """
                return mock.proceed()
                """), "test");
        // The this() chain weaves both <init>; both complete and the object is initialized.
        Chained chained = new Chained("safe");
        assertThat(chained.tag).isEqualTo("chained:safe");
        assertThat(new Chained().tag).isEqualTo("chained:default");
    }

    @Test
    void constructorThrowLocationReplacesException() throws Exception {
        Constructor<?> ctor = ThrowingCtor.class.getDeclaredConstructor();
        runtime.publishConstructor(ctor, constructorRule("ctor-throw", ctor,
                EnhancementLocation.CONSTRUCTOR_THROW, """
                return mock.throwException('java.lang.IllegalStateException', 'ctor-replaced')
                """), "test");
        assertThatThrownBy(ThrowingCtor::new)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ctor-replaced");
    }

    @Test
    void constructorReturnLocationIsObserveOnlyAndRestoresBytecode() throws Exception {
        Constructor<?> ctor = Derived.class.getDeclaredConstructor();
        ClassIdentity identity = ClassIdentities.of(Derived.class);
        byte[] baseline = runtime.captureService().capture(Derived.class).appliedBytes();
        String beforeHash = BytecodeHash.sha256Hex(baseline);

        runtime.publishConstructor(ctor, constructorRule("ctor-return", ctor,
                EnhancementLocation.CONSTRUCTOR_RETURN, """
                return mock.proceed()
                """), "test");
        // CONSTRUCTOR_RETURN dispatches on normal return and is observe-only: the rule
        // is woven (bytecode changes) but cannot substitute the constructed object, so the
        // object still constructs with its real state.
        String enhancedHash = runtime.captureService().capture(Derived.class).appliedHash();
        assertThat(enhancedHash).isNotEqualTo(beforeHash);
        assertThat(new Derived().tag).isEqualTo("derived");

        runtime.remove("ctor-return", "test");
        assertThat(runtime.captureService().capture(Derived.class).appliedHash()).isEqualTo(beforeHash);
    }

    // -------------------------------------------------------- call-site locations

    @Test
    void callSiteBeforeShortCircuitsSecondOfThreeOccurrences() throws Exception {
        Method caller = CallSiteSamples.class.getMethod("threeCalls");
        MockRule rule = MockRule.builder()
                .id("call-2nd")
                .name("call-2nd")
                .target(selectorOf(caller))
                .location(EnhancementLocation.CALL_BEFORE)
                .callSiteSelector(CallSiteSelector.builder()
                        .owner(CallSiteSamples.class.getName())
                        .name("calleeThree")
                        .descriptor("()Ljava/lang/String;")
                        .opcode(InvokeOpcode.INVOKEVIRTUAL)
                        .occurrenceIndex(1)
                        .build())
                .script("return mock.returnValue('M')")
                .priority(100)
                .percentage(100)
                .failOpen(true)
                .enabled(true)
                .build();
        runtime.publish(caller, rule, "test");
        // occurrence 1 is the 2nd of three; only it is short-circuited to 'M'.
        assertThat(new CallSiteSamples().threeCalls()).isEqualTo("cMc");
    }

    @Test
    void callSiteReturnReplacesVirtualCallResult() throws Exception {
        Method caller = CallSiteSamples.class.getMethod("callVirtual");
        MockRule rule = callSiteRule("call-ret", caller, EnhancementLocation.CALL_RETURN,
                CallSiteSamples.class.getName(), "virtualTarget", "()Ljava/lang/String;",
                InvokeOpcode.INVOKEVIRTUAL, 0,
                "return mock.returnValue('VIRT-MOCKED')");
        runtime.publish(caller, rule, "test");
        assertThat(new CallSiteSamples().callVirtual()).isEqualTo("VIRT-MOCKED");
    }

    @Test
    void callSiteBeforeOnStaticCall() throws Exception {
        Method caller = CallSiteSamples.class.getMethod("callStatic");
        MockRule rule = callSiteRule("call-static", caller, EnhancementLocation.CALL_BEFORE,
                CallSiteSamples.class.getName(), "staticTarget", "()Ljava/lang/String;",
                InvokeOpcode.INVOKESTATIC, 0,
                "return mock.returnValue('STATIC-CALL-MOCKED')");
        runtime.publish(caller, rule, "test");
        assertThat(new CallSiteSamples().callStatic()).isEqualTo("STATIC-CALL-MOCKED");
    }

    @Test
    void callSiteBeforeOnInterfaceCall() throws Exception {
        Method caller = com.example.demo.v13.EnhancementFixtures.class.getMethod("callInterface",
                com.example.demo.v13.EnhancementFixtures.Greeter.class);
        MockRule rule = callSiteRule("call-iface", caller, EnhancementLocation.CALL_BEFORE,
                com.example.demo.v13.EnhancementFixtures.Greeter.class.getName(), "greet",
                "()Ljava/lang/String;", InvokeOpcode.INVOKEINTERFACE, 0,
                "return mock.returnValue('IFACE-MOCKED')");
        runtime.publish(caller, rule, "test");
        assertThat(com.example.demo.v13.EnhancementFixtures.callInterface(new GreeterImpl())).isEqualTo("IFACE-MOCKED");
    }

    @Test
    void callSiteBeforeOnInvokeSpecialSuperCall() throws Exception {
        Method caller = SuperCaller.class.getMethod("callSuper");
        MockRule rule = callSiteRule("call-special", caller, EnhancementLocation.CALL_BEFORE,
                SuperBase.class.getName(), "label", "()Ljava/lang/String;",
                InvokeOpcode.INVOKESPECIAL, 0,
                "return mock.returnValue('SUPER-MOCKED')");
        runtime.publish(caller, rule, "test");
        assertThat(new SuperCaller().callSuper()).isEqualTo("SUPER-MOCKED");
    }

    @Test
    void callSiteThrowInsideTryCatchIsHandled() throws Exception {
        Method caller = CallSiteSamples.class.getMethod("callInTryCatch");
        MockRule rule = callSiteRule("call-throw", caller, EnhancementLocation.CALL_BEFORE,
                CallSiteSamples.class.getName(), "boom", "()Ljava/lang/String;",
                InvokeOpcode.INVOKEVIRTUAL, 0,
                "return mock.throwException('java.lang.IllegalStateException', 'call-replaced')");
        runtime.publish(caller, rule, "test");
        // CALL_BEFORE throws 'call-replaced'; the caller's try/catch catches it.
        assertThat(new CallSiteSamples().callInTryCatch()).isEqualTo("caught:call-replaced");
    }

    @Test
    void callThrowLocationReplacesCalleeException() throws Exception {
        Method caller = CallSiteSamples.class.getMethod("callInTryCatch");
        MockRule rule = callSiteRule("call-throw-exit", caller, EnhancementLocation.CALL_THROW,
                CallSiteSamples.class.getName(), "boom", "()Ljava/lang/String;",
                InvokeOpcode.INVOKEVIRTUAL, 0,
                "return mock.throwException('java.lang.IllegalStateException', 'call-throw-replaced')");
        runtime.publish(caller, rule, "test");
        // boom() throws naturally; CALL_THROW fires on the callee throwable and replaces
        // it; the caller's try/catch catches the replacement.
        assertThat(new CallSiteSamples().callInTryCatch()).isEqualTo("caught:call-throw-replaced");
    }

    @Test
    void callSiteBeforeCanReplaceCallArguments() throws Exception {
        Method caller = CallSiteSamples.class.getMethod("callOverloaded");
        // echo(String) is occurrence 0 of invokevirtual echo(String); replace its arg.
        MockRule rule = callSiteRule("call-args", caller, EnhancementLocation.CALL_BEFORE,
                CallSiteSamples.class.getName(), "echo", "(Ljava/lang/String;)Ljava/lang/String;",
                InvokeOpcode.INVOKEVIRTUAL, 0,
                "return mock.proceed(['Z'] as Object[])");
        runtime.publish(caller, rule, "test");
        // echo('Z') = 's:Z' ; echo(1) = 'i:1'
        assertThat(new CallSiteSamples().callOverloaded()).isEqualTo("s:Zi:1");
    }

    // -------------------------------------------------------- drift + rejection

    @Test
    void callSiteDriftIsRejectedAfterRecompilation() throws Exception {
        Method v1CallerMethod = DriftV1.class.getMethod("driftCaller", DriftCallee.class);
        MethodSelector v1Caller = selectorOf(v1CallerMethod);
        String calleeOwner = DriftCallee.class.getName();
        // Capture the identity from V1.
        List<CallSiteIdentity> v1Hits = runtime.callSiteScanner().scan(DriftV1.class, v1Caller,
                calleeOwner, "driftCallee", "()Ljava/lang/String;", InvokeOpcode.INVOKEVIRTUAL);
        assertThat(v1Hits).hasSize(1);
        CallSiteIdentity v1Identity = v1Hits.get(0);
        assertThat(v1Identity.selector().fingerprint()).isNotBlank();

        // Re-resolve against V1: matched.
        TargetMatchResult v1Result = runtime.callSiteScanner().resolveCallSite(DriftV1.class, v1Caller,
                v1Identity.selector());
        assertThat(v1Result.status()).isEqualTo(TargetMatchResult.Status.MATCHED);

        // Re-resolve the SAME recorded identity against V2 (different surrounding opcodes): drifted.
        TargetMatchResult v2Result = runtime.callSiteScanner().resolveCallSite(DriftV2.class, v1Caller,
                v1Identity.selector());
        assertThat(v2Result.status()).isEqualTo(TargetMatchResult.Status.DRIFTED);
    }

    @Test
    void nativeAndAbstractMethodsAreRejectedEarly() throws Exception {
        Method nativeMethod = NativeHolder.class.getMethod("nativeMethod");
        assertThatThrownBy(() -> runtime.publish(nativeMethod,
                rule("native", nativeMethod, EnhancementLocation.METHOD_RETURN, "return mock.proceed()"), "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Native");

        Method abstractMethod = NativeHolder.AbstractHolder.class.getMethod("abstractMethod");
        assertThatThrownBy(() -> runtime.publish(abstractMethod,
                rule("abstract", abstractMethod, EnhancementLocation.METHOD_RETURN, "return mock.proceed()"), "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Abstract");
    }

    @Test
    void resolveTargetReportsNativeAndAbstractAsRejected() throws Exception {
        MethodSelector nativeSelector = new MethodSelector(NativeHolder.class.getName(),
                ClassLoaderIdentity.idOf(NativeHolder.class.getClassLoader()),
                "nativeMethod", "()V");
        TargetMatchResult nativeResult = runtime.resolveTarget(NativeHolder.class,
                com.example.kairo.api.EnhancementTarget.of(nativeSelector, EnhancementLocation.METHOD_RETURN));
        assertThat(nativeResult.status()).isEqualTo(TargetMatchResult.Status.REJECTED);

        MethodSelector abstractSelector = new MethodSelector(NativeHolder.AbstractHolder.class.getName(),
                ClassLoaderIdentity.idOf(NativeHolder.AbstractHolder.class.getClassLoader()),
                "abstractMethod", "()Ljava/lang/String;");
        TargetMatchResult abstractResult = runtime.resolveTarget(NativeHolder.AbstractHolder.class,
                com.example.kairo.api.EnhancementTarget.of(abstractSelector, EnhancementLocation.METHOD_RETURN));
        assertThat(abstractResult.status()).isEqualTo(TargetMatchResult.Status.REJECTED);
    }

    @Test
    void invokedynamicCallSiteOpcodeIsRejected() throws Exception {
        Method caller = CallSiteSamples.class.getMethod("threeCalls");
        MockRule rule = MockRule.builder()
                .id("indy")
                .name("indy")
                .target(selectorOf(caller))
                .location(EnhancementLocation.CALL_BEFORE)
                .callSiteSelector(CallSiteSelector.builder()
                        .owner(CallSiteSamples.class.getName())
                        .name("calleeThree")
                        .descriptor("()Ljava/lang/String;")
                        .opcode(InvokeOpcode.INVOKEDYNAMIC)
                        .occurrenceIndex(0)
                        .build())
                .script("return mock.proceed()")
                .priority(100)
                .percentage(100)
                .failOpen(true)
                .enabled(true)
                .build();
        assertThatThrownBy(() -> runtime.publish(caller, rule, "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported invoke opcode");
    }

    // -------------------------------------------------------- V1.0 compat + unload

    @Test
    void v1RuleWithoutLocationRunsUnmigrated() throws Exception {
        Method method = MethodKinds.class.getMethod("valueMethod", int.class);
        // A V1.0 rule authored with only a phase lands on METHOD_RETURN exactly as before.
        runtime.publish(method, legacyRule("v1", method, InvokePhase.RETURN,
                "return mock.returnValue('V1-MOCKED')"), "test");
        assertThat(new MethodKinds().valueMethod(1)).isEqualTo("V1-MOCKED");
    }

    @Test
    void callSiteEnhancementAndPerLocationUnloadRestoresBytecode() throws Exception {
        Method caller = CallSiteSamples.class.getMethod("callVirtual");
        ClassIdentity identity = ClassIdentities.of(CallSiteSamples.class);
        byte[] baseline = runtime.captureService().capture(CallSiteSamples.class).appliedBytes();
        String beforeHash = BytecodeHash.sha256Hex(baseline);

        MockRule rule = callSiteRule("unload", caller, EnhancementLocation.CALL_BEFORE,
                CallSiteSamples.class.getName(), "virtualTarget", "()Ljava/lang/String;",
                InvokeOpcode.INVOKEVIRTUAL, 0, "return mock.returnValue('UNLOAD-MOCKED')");
        runtime.publish(caller, rule, "test");

        // Enhanced: the call site is short-circuited.
        assertThat(new CallSiteSamples().callVirtual()).isEqualTo("UNLOAD-MOCKED");
        String enhancedHash = runtime.captureService().capture(CallSiteSamples.class).appliedHash();
        assertThat(enhancedHash).isNotEqualTo(beforeHash);

        // The diff locates the modified method.
        BytecodeDiffResult diff = runtime.diffService().diff(identity, baseline,
                runtime.captureService().capture(CallSiteSamples.class).revision(),
                BytecodeSnapshotKind.INPUT,
                runtime.captureService().capture(CallSiteSamples.class).appliedBytes(),
                runtime.captureService().capture(CallSiteSamples.class).revision(),
                BytecodeSnapshotKind.APPLIED);
        assertThat(diff.identical()).isFalse();
        assertThat(diff.methodDiffs()).extracting(m -> m.methodName() + m.methodDescriptor())
                .contains("callVirtual()Ljava/lang/String;");
        String instructions = String.join("\n", diff.methodDiffs().stream()
                .filter(m -> m.methodName().equals("callVirtual")).findFirst().orElseThrow()
                .instructionDiffs());
        assertThat(instructions).contains("CallSiteBridge");

        // Per-location unload restores the original bytes.
        runtime.remove("unload", "test");
        String afterUnloadHash = runtime.captureService().capture(CallSiteSamples.class).appliedHash();
        assertThat(afterUnloadHash).isEqualTo(beforeHash);
        assertThat(new CallSiteSamples().callVirtual()).isEqualTo("virtual");
    }

    @Test
    void constructorEnhancementAndUnloadRestoresBytecode() throws Exception {
        Constructor<?> ctor = Derived.class.getDeclaredConstructor();
        ClassIdentity identity = ClassIdentities.of(Derived.class);
        byte[] baseline = runtime.captureService().capture(Derived.class).appliedBytes();
        String beforeHash = BytecodeHash.sha256Hex(baseline);

        runtime.publishConstructor(ctor, constructorRule("ctor-unload", ctor,
                EnhancementLocation.CONSTRUCTOR_AFTER_SUPER, "return mock.proceed()"), "test");
        String enhancedHash = runtime.captureService().capture(Derived.class).appliedHash();
        assertThat(enhancedHash).isNotEqualTo(beforeHash);
        // Object still constructs correctly.
        assertThat(new Derived().tag).isEqualTo("derived");

        runtime.remove("ctor-unload", "test");
        String afterUnloadHash = runtime.captureService().capture(Derived.class).appliedHash();
        assertThat(afterUnloadHash).isEqualTo(beforeHash);
    }

    // -------------------------------------------------------- helpers

    private static MethodSelector selectorOf(Method method) {
        return MethodSelector.builder()
                .className(method.getDeclaringClass().getName())
                .classLoaderId(ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()))
                .methodName(method.getName())
                .methodDescriptor(MethodDescriptor.of(method))
                .build();
    }

    private static MockRule rule(String id, Method method, EnhancementLocation location, String script) {
        return MockRule.builder()
                .id(id)
                .name(id)
                .target(selectorOf(method))
                .location(location)
                .script(script)
                .priority(100)
                .percentage(100)
                .failOpen(true)
                .enabled(true)
                .build();
    }

    private static MockRule legacyRule(String id, Method method, InvokePhase phase, String script) {
        return MockRule.builder()
                .id(id)
                .name(id)
                .target(selectorOf(method))
                .phase(phase)
                .script(script)
                .priority(100)
                .percentage(100)
                .failOpen(true)
                .enabled(true)
                .build();
    }

    private static MockRule constructorRule(String id, Constructor<?> constructor,
                                            EnhancementLocation location, String script) {
        return MockRule.builder()
                .id(id)
                .name(id)
                .target(MethodSelector.builder()
                        .className(constructor.getDeclaringClass().getName())
                        .classLoaderId(ClassLoaderIdentity.idOf(constructor.getDeclaringClass().getClassLoader()))
                        .methodName("<init>")
                        .methodDescriptor(MethodDescriptor.of(constructor))
                        .build())
                .location(location)
                .script(script)
                .priority(100)
                .percentage(100)
                .failOpen(true)
                .enabled(true)
                .build();
    }

    private static MockRule callSiteRule(String id, Method caller, EnhancementLocation location,
                                         String calleeOwner, String calleeName, String calleeDescriptor,
                                         InvokeOpcode opcode, int occurrence, String script) {
        return MockRule.builder()
                .id(id)
                .name(id)
                .target(selectorOf(caller))
                .location(location)
                .callSiteSelector(CallSiteSelector.builder()
                        .owner(calleeOwner)
                        .name(calleeName)
                        .descriptor(calleeDescriptor)
                        .opcode(opcode)
                        .occurrenceIndex(occurrence)
                        .build())
                .script(script)
                .priority(100)
                .percentage(100)
                .failOpen(true)
                .enabled(true)
                .build();
    }
}
