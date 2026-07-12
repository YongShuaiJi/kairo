package com.example.demo.v13;

/**
 * Fixtures for V1.3 enhancement-location acceptance: method kinds, constructor
 * kinds and call-site samples. Lives under {@code com.example.demo} (not
 * {@code com.example.kairo}) so the agent transformer does not ignore it.
 */
public final class EnhancementFixtures {

    private EnhancementFixtures() {
    }

    // ---- method kinds ----

    public static class MethodKinds {
        public static String staticTarget(String s) {
            return "static-" + s;
        }

        public String valueMethod(int v) {
            return "value-" + v;
        }

        public void voidMethod(String s) {
            // observable side effect
            lastVoidArg = s;
        }

        public final String finalMethod(String s) {
            return "final-" + s;
        }

        public synchronized String synchronizedMethod(String s) {
            return "sync-" + s;
        }

        private String privateMethod(String s) {
            return "private-" + s;
        }

        public String callPrivate(String s) {
            return privateMethod(s);
        }

        public static String lastVoidArg;
    }

    public interface DefaultInterface {
        default String defaultMethod(String s) {
            return "default-" + s;
        }
    }

    public static class DefaultInterfaceImpl implements DefaultInterface {
        @Override
        public String defaultMethod(String s) {
            return "default-" + s;
        }
    }

    // ---- constructor kinds ----

    public static class Base {
        public String tag;

        public Base() {
            this.tag = "base";
        }

        public Base(String tag) {
            this.tag = "base:" + tag;
        }
    }

    public static class Derived extends Base {
        public Derived() {
            super();            // invokespecial Base.<init>
            this.tag = "derived";
        }
    }

    public static class Chained {
        public String tag;

        public Chained() {
            this("default");    // invokespecial Chained.<init>(String)
        }

        public Chained(String tag) {
            this.tag = "chained:" + tag;
        }
    }

    public static class ThrowingCtor {
        public ThrowingCtor() {
            throw new IllegalStateException("ctor-origin");
        }
    }

    // ---- call-site samples ----

    public static class CallSiteSamples {
        public String calleeThree() {
            return "c";
        }

        public String threeCalls() {
            // three invokevirtual calleeThree() in visit order; occurrence 1 is the 2nd.
            return calleeThree() + calleeThree() + calleeThree();
        }

        public String virtualTarget() {
            return "virtual";
        }

        public String callVirtual() {
            return virtualTarget();
        }

        public static String staticTarget() {
            return "static";
        }

        public String callStatic() {
            return staticTarget();
        }

        public String echo(String s) {
            return "s:" + s;
        }

        public String echo(int i) {
            return "i:" + i;
        }

        public String callOverloaded() {
            return echo("x") + echo(1);
        }

        public String boom() {
            throw new IllegalStateException("boom-origin");
        }

        public String callInTryCatch() {
            try {
                return boom();
            } catch (RuntimeException e) {
                return "caught:" + e.getMessage();
            }
        }

        private String privateTarget() {
            return "private";
        }

        public String callPrivateSite() {
            return privateTarget();
        }
    }

    public interface Greeter {
        String greet();
    }

    public static class GreeterImpl implements Greeter {
        @Override
        public String greet() {
            return "hi";
        }
    }

    public static String callInterface(Greeter g) {
        return g.greet();
    }

    // ---- drift fixtures: same caller method + callee, different surrounding opcodes ----

    public static class DriftCallee {
        public String driftCallee() {
            return "v";
        }
    }

    public static class DriftV1 {
        public String driftCaller(DriftCallee c) {
            int x = 1;                 // ICONST_1 ISTORE
            String r = c.driftCallee();
            return r + x;
        }
    }

    public static class DriftV2 {
        public String driftCaller(DriftCallee c) {
            double x = 1.0d;           // DCONST_1 DSTORE (different opcodes)
            String r = c.driftCallee();
            return r + x;
        }
    }

    // ---- invokespecial via super-call ----

    public static class SuperBase {
        public String label() {
            return "base-label";
        }
    }

    public static class SuperCaller extends SuperBase {
        public String callSuper() {
            return super.label();      // invokespecial SuperBase.label
        }
    }

    // ---- native / abstract rejection fixtures ----

    public static class NativeHolder {
        public static native void nativeMethod();

        public static abstract class AbstractHolder {
            public abstract String abstractMethod();
        }
    }
}
