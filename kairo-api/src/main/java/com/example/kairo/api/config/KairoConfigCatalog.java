package com.example.kairo.api.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Structured source of truth for the public V1 configuration contract. A binding is identified by
 * channel, component and key because the same environment variable may have different effective
 * defaults in different components. Source discovery is used only as a coverage guard; it is not
 * the contract itself.
 */
public final class KairoConfigCatalog {

    public static final String REDACTED = "<redacted>";

    public enum Channel {
        SPRING_PROPERTY,
        ENVIRONMENT
    }

    public enum ValueType {
        STRING,
        BOOLEAN,
        INTEGER,
        LONG,
        URL,
        JSON
    }

    public record Identity(Channel channel, String component, String key) {
        public Identity {
            Objects.requireNonNull(channel, "channel");
            requireText(component, "component");
            requireText(key, "key");
        }
    }

    public record Binding(Channel channel, String component, String key, ValueType type,
                          String defaultValue, boolean defaultPresent, boolean sensitive,
                          boolean deprecated, String replacement) {
        public Binding {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(type, "type");
            requireText(component, "component");
            requireText(key, "key");
            Objects.requireNonNull(defaultValue, "defaultValue");
            replacement = replacement == null ? "" : replacement;
            if (sensitive && !REDACTED.equals(defaultValue)) {
                throw new IllegalArgumentException("Sensitive default must be redacted: " + key);
            }
            if (deprecated && replacement.isBlank()) {
                throw new IllegalArgumentException("Deprecated binding requires replacement: " + key);
            }
            if (!deprecated && !replacement.isBlank()) {
                throw new IllegalArgumentException("Non-deprecated binding cannot have replacement: " + key);
            }
        }

        public Identity identity() {
            return new Identity(channel, component, key);
        }
    }

    private static final List<Binding> ENTRIES = build();
    private static final Map<Identity, Binding> BY_IDENTITY = index(ENTRIES);
    private static final Set<Identity> IDENTITIES = Collections.unmodifiableSet(
            new LinkedHashSet<>(BY_IDENTITY.keySet()));

    private KairoConfigCatalog() {
    }

    public static List<Binding> entries() {
        return ENTRIES;
    }

    public static Set<Identity> identities() {
        return IDENTITIES;
    }

    public static Binding require(Channel channel, String component, String key) {
        Identity identity = new Identity(channel, component, key);
        Binding binding = BY_IDENTITY.get(identity);
        if (binding == null) {
            throw new IllegalArgumentException("Unknown public configuration binding: " + identity);
        }
        return binding;
    }

    private static List<Binding> build() {
        List<Binding> out = new ArrayList<>();

        // Spring properties consumed by kairo-platform-server (application.yml + direct bindings).
        spring(out, "platform", "kairo.platform.api.enabled", ValueType.BOOLEAN, "true");
        spring(out, "platform", "kairo.platform.fencing.redis-enabled", ValueType.BOOLEAN, "false");
        spring(out, "platform", "kairo.platform.fencing.key-prefix", ValueType.STRING, "kairo:fencing:");
        spring(out, "platform", "kairo.platform.fencing.default-ttl-seconds", ValueType.LONG, "300");
        spring(out, "platform", "kairo.platform.rollout.scheduler.enabled", ValueType.BOOLEAN, "true");
        spring(out, "platform", "kairo.platform.rollout.scheduler.fixed-delay-ms", ValueType.LONG, "3000");
        spring(out, "platform", "kairo.platform.rollout.scheduler.command-lease-seconds", ValueType.LONG, "60");
        spring(out, "platform", "kairo.platform.reconciliation.scheduler.enabled", ValueType.BOOLEAN, "true");
        spring(out, "platform", "kairo.platform.reconciliation.scheduler.fixed-delay-ms", ValueType.LONG, "30000");
        spring(out, "platform", "kairo.platform.reconciliation.scheduler.initial-delay-ms", ValueType.LONG, "30000");
        spring(out, "platform", "kairo.platform.reconciliation.snapshot-request-delay-ms", ValueType.LONG, "5000");
        spring(out, "platform", "kairo.platform.attach.agent-jar", ValueType.STRING,
                "/app/kairo-agent-bootstrap.jar");
        spring(out, "platform", "kairo.platform.attach.core-jar", ValueType.STRING,
                "/app/kairo-agent-core-modern.jar");
        spring(out, "platform", "kairo.platform.attach.bootstrap-jar", ValueType.STRING,
                "/app/kairo-bootstrap-api.jar");
        spring(out, "platform", "kairo.platform.attach.agent-host", ValueType.STRING, "127.0.0.1");
        spring(out, "platform", "kairo.platform.attach.agent-port", ValueType.INTEGER, "0");
        spring(out, "platform", "kairo.platform.attach.platform-url", ValueType.URL,
                "http://127.0.0.1:18280");
        secretSpring(out, "platform", "kairo.platform.attach.platform-token", true);
        secretSpring(out, "platform", "kairo.platform.attach.sidecar-token", true);
        spring(out, "platform", "kairo.platform.attach.command-max-attempts", ValueType.INTEGER, "3");
        spring(out, "platform", "kairo.platform.runtime-cleanup.retention-ms", ValueType.LONG, "1800000");
        spring(out, "platform", "kairo.platform.runtime-cleanup.initial-delay-ms", ValueType.LONG, "60000");
        spring(out, "platform", "kairo.platform.runtime-cleanup.fixed-delay-ms", ValueType.LONG, "60000");
        spring(out, "platform", "kairo.platform.auth.mode", ValueType.STRING, "local-token");
        secretSpring(out, "platform", "kairo.platform.auth.bootstrap-token", true);
        spring(out, "platform", "kairo.platform.auth.bootstrap-actor", ValueType.STRING, "system");
        spring(out, "platform", "kairo.platform.auth.bootstrap-ttl-days", ValueType.LONG, "365");
        spring(out, "platform", "management.endpoints.web.exposure.include", ValueType.STRING,
                "health,info,metrics");
        spring(out, "platform", "management.endpoint.health.probes.enabled", ValueType.BOOLEAN, "true");
        // V1.7 M4-A &sect;11.1: liveness is exclusively process-liveness (livenessState); readiness requires
        // the readiness state, DB, a real Flyway validate and (only when fencing requires it) Redis.
        spring(out, "platform", "management.endpoint.health.show-details", ValueType.STRING, "always");
        spring(out, "platform", "management.endpoint.health.group.liveness.include", ValueType.STRING,
                "livenessState");
        spring(out, "platform", "management.endpoint.health.group.readiness.include", ValueType.STRING,
                "readinessState,db,flyway,redis");
        spring(out, "platform", "management.health.redis.enabled", ValueType.BOOLEAN, "false");
        spring(out, "platform", "management.health.flyway.enabled", ValueType.BOOLEAN, "false");
        // Auto build/git/env/java/os info contributors are disabled; KairoBuildInfoContributor owns a single
        // bounded, secret-free build-identity object (&sect;11.1). Auto build/git beans still load so the
        // contributor can read standard BuildProperties/GitProperties when they are generated.
        spring(out, "platform", "management.info.build.enabled", ValueType.BOOLEAN, "false");
        spring(out, "platform", "management.info.git.enabled", ValueType.BOOLEAN, "false");
        spring(out, "platform", "management.info.env.enabled", ValueType.BOOLEAN, "false");
        spring(out, "platform", "management.info.java.enabled", ValueType.BOOLEAN, "false");
        spring(out, "platform", "management.info.os.enabled", ValueType.BOOLEAN, "false");

        spring(out, "platform", "kairo.platform.automation.expiry.fixed-delay-ms", ValueType.LONG, "10000");
        spring(out, "platform", "kairo.platform.automation.expiry.initial-delay-ms", ValueType.LONG, "10000");
        spring(out, "platform", "kairo.platform.bytecode.timeout-ms", ValueType.LONG, "15000");
        spring(out, "platform", "kairo.platform.idempotency.lease-millis", ValueType.LONG, "30000");
        spring(out, "platform", "kairo.platform.idempotency.renew-millis", ValueType.LONG, "10000");
        spring(out, "platform", "kairo.platform.idempotency.max-wait-millis", ValueType.LONG, "35000");
        spring(out, "platform", "kairo.platform.idempotency.poll-millis", ValueType.LONG, "25");
        spring(out, "platform", "kairo.platform.runtime-lease.fixed-delay-ms", ValueType.LONG, "5000");
        spring(out, "platform", "kairo.platform.runtime-lease.initial-delay-ms", ValueType.LONG, "5000");
        spring(out, "platform", "kairo.platform.script.command-timeout-ms", ValueType.LONG, "15000");
        spring(out, "platform", "kairo.platform.script.max-ttl-millis", ValueType.LONG, "300000");
        spring(out, "platform", "kairo.platform.script.max-hits", ValueType.LONG, "100");
        spring(out, "platform", "kairo.platform.script.default-ttl-millis", ValueType.LONG, "60000");
        spring(out, "platform", "kairo.platform.script.default-max-hits", ValueType.LONG, "1");
        spring(out, "platform", "kairo.platform.script.platform-max-profile", ValueType.STRING, "");
        spring(out, "platform", "kairo.platform.script.expiry.fixed-delay-ms", ValueType.LONG, "5000");
        spring(out, "platform", "kairo.platform.script.expiry.initial-delay-ms", ValueType.LONG, "5000");
        spring(out, "platform", "kairo.platform.target-resolution.timeout-ms", ValueType.LONG, "10000");

        // Environment bindings exposed by the Platform deployment.
        env(out, "platform", "KAIRO_DB_URL", ValueType.URL,
                "jdbc:postgresql://127.0.0.1:5432/kairo", true);
        env(out, "platform", "KAIRO_DB_USER", ValueType.STRING, "kairo", true);
        secretEnv(out, "platform", "KAIRO_DB_PASSWORD", true);
        env(out, "platform", "KAIRO_REDIS_HOST", ValueType.STRING, "127.0.0.1", true);
        env(out, "platform", "KAIRO_REDIS_PORT", ValueType.INTEGER, "6379", true);
        env(out, "platform", "KAIRO_API_ENABLED", ValueType.BOOLEAN, "true", true);
        env(out, "platform", "KAIRO_FENCING_REDIS_ENABLED", ValueType.BOOLEAN, "false", true);
        env(out, "platform", "KAIRO_FENCING_KEY_PREFIX", ValueType.STRING, "kairo:fencing:", true);
        env(out, "platform", "KAIRO_FENCING_DEFAULT_TTL_SECONDS", ValueType.LONG, "300", true);
        env(out, "platform", "KAIRO_ROLLOUT_SCHEDULER_ENABLED", ValueType.BOOLEAN, "true", true);
        env(out, "platform", "KAIRO_ROLLOUT_SCHEDULER_FIXED_DELAY_MS", ValueType.LONG, "3000", true);
        env(out, "platform", "KAIRO_AGENT_COMMAND_LEASE_SECONDS", ValueType.LONG, "60", true);
        env(out, "platform", "KAIRO_ATTACH_AGENT_JAR", ValueType.STRING,
                "/app/kairo-agent-bootstrap.jar", true);
        env(out, "platform", "KAIRO_ATTACH_CORE_JAR", ValueType.STRING,
                "/app/kairo-agent-core-modern.jar", true);
        env(out, "platform", "KAIRO_ATTACH_BOOTSTRAP_JAR", ValueType.STRING,
                "/app/kairo-bootstrap-api.jar", true);
        env(out, "platform", "KAIRO_ATTACH_AGENT_HOST", ValueType.STRING, "127.0.0.1", true);
        env(out, "platform", "KAIRO_ATTACH_AGENT_PORT", ValueType.INTEGER, "0", true);
        env(out, "platform", "KAIRO_ATTACH_PLATFORM_URL", ValueType.URL,
                "http://127.0.0.1:18280", true);
        secretEnv(out, "platform", "KAIRO_ATTACH_PLATFORM_TOKEN", true);
        secretEnv(out, "platform", "KAIRO_ATTACH_SIDECAR_TOKEN", true);
        env(out, "platform", "KAIRO_ATTACH_COMMAND_MAX_ATTEMPTS", ValueType.INTEGER, "3", true);
        env(out, "platform", "KAIRO_RUNTIME_CLEANUP_RETENTION_MS", ValueType.LONG, "1800000", true);
        env(out, "platform", "KAIRO_RUNTIME_CLEANUP_INITIAL_DELAY_MS", ValueType.LONG, "60000", true);
        env(out, "platform", "KAIRO_RUNTIME_CLEANUP_FIXED_DELAY_MS", ValueType.LONG, "60000", true);
        env(out, "platform", "KAIRO_AUTH_MODE", ValueType.STRING, "local-token", true);
        secretEnv(out, "platform", "KAIRO_BOOTSTRAP_TOKEN", true);
        env(out, "platform", "KAIRO_BOOTSTRAP_ACTOR", ValueType.STRING, "system", true);
        env(out, "platform", "KAIRO_BOOTSTRAP_TTL_DAYS", ValueType.LONG, "365", true);

        // Attach sidecar environment contract.
        env(out, "sidecar", "KAIRO_APPLICATION_NAME", ValueType.STRING, "kairo-demo", true);
        env(out, "sidecar", "KAIRO_TARGET_PID", ValueType.STRING, "1", true);
        env(out, "sidecar", "KAIRO_PROCESS_START_ID", ValueType.STRING,
                "<applicationName>:<hostname>:<targetPid>", true);
        env(out, "sidecar", "KAIRO_AGENT_JAR", ValueType.STRING,
                "/app/kairo-agent-bootstrap.jar", true);
        env(out, "sidecar", "KAIRO_EXECUTOR_ID", ValueType.STRING, "executor-<hostname>", true);
        env(out, "sidecar", "KAIRO_SIDECAR_HOST", ValueType.STRING, "0.0.0.0", true);
        env(out, "sidecar", "KAIRO_SIDECAR_PORT", ValueType.INTEGER, "18480", true);
        env(out, "sidecar", "KAIRO_PLATFORM_URL", ValueType.URL, "http://platform:18280", true);
        secretEnv(out, "sidecar", "KAIRO_PLATFORM_TOKEN", true);
        secretEnv(out, "sidecar", "KAIRO_SIDECAR_TOKEN", true);
        env(out, "sidecar", "KAIRO_EXECUTOR_TYPE", ValueType.STRING, "SIDECAR_CONTAINER", true);
        env(out, "sidecar", "KAIRO_SIDECAR_ENDPOINT", ValueType.URL,
                "http://localhost:18480", true);
        env(out, "sidecar", "KAIRO_SIDECAR_VERSION", ValueType.STRING, "0.1.0-SNAPSHOT", true);
        env(out, "sidecar", "KAIRO_EXECUTOR_POLL_WAIT_MILLIS", ValueType.LONG, "25000", true);
        env(out, "sidecar", "KAIRO_EXECUTOR_COMMAND_LEASE_SECONDS", ValueType.LONG, "30", true);
        env(out, "sidecar", "KAIRO_TARGETS_JSON", ValueType.JSON, "", false);
        env(out, "sidecar", "KAIRO_PROJECT_NAME", ValueType.STRING, "kairo", true);
        env(out, "sidecar", "KAIRO_ENVIRONMENT_NAME", ValueType.STRING, "SIT", true);
        env(out, "sidecar", "KAIRO_INSTANCE_NICKNAME", ValueType.STRING,
                "<applicationName>", true);
        env(out, "sidecar", "KAIRO_TARGET_RUNTIME", ValueType.STRING, "java", true);
        env(out, "sidecar", "KAIRO_TARGET_JAVA_VERSION", ValueType.STRING, "unknown", true);

        // CLI/MCP resolve missing values through ~/.kairo/credentials, not an environment default.
        env(out, "cli", "KAIRO_PLATFORM_URL", ValueType.URL, "", false);
        secretEnv(out, "cli", "KAIRO_TOKEN", false);
        env(out, "mcp", "KAIRO_PLATFORM_URL", ValueType.URL, "", false);
        secretEnv(out, "mcp", "KAIRO_TOKEN", false);
        env(out, "ops", "KAIRO_OPS_AUDIT_PATH", ValueType.STRING, "", false);

        env(out, "web", "KAIRO_PLATFORM_API_URL", ValueType.URL,
                "http://127.0.0.1:18280", true);
        secretEnv(out, "web", "KAIRO_WEB_SESSION_KEY", false);
        env(out, "web", "KAIRO_WEB_PUBLIC_BASE_URL", ValueType.URL,
                "http://127.0.0.1:18380", true);
        env(out, "web", "KAIRO_WEB_ENVIRONMENT", ValueType.STRING, "local", true);
        env(out, "web", "KAIRO_WEB_DEMO_MODE", ValueType.BOOLEAN, "false", true);

        env(out, "smoke", "KAIRO_API", ValueType.URL,
                "http://127.0.0.1:18280/api/v1", true);
        secretEnv(out, "smoke", "KAIRO_TOKEN", true);
        env(out, "smoke", "KAIRO_ACTOR", ValueType.STRING, "system", true);

        return List.copyOf(out);
    }

    private static void spring(List<Binding> out, String component, String key,
                               ValueType type, String defaultValue) {
        out.add(new Binding(Channel.SPRING_PROPERTY, component, key, type, defaultValue,
                true, false, false, ""));
    }

    private static void secretSpring(List<Binding> out, String component, String key,
                                     boolean defaultPresent) {
        out.add(new Binding(Channel.SPRING_PROPERTY, component, key, ValueType.STRING, REDACTED,
                defaultPresent, true, false, ""));
    }

    private static void env(List<Binding> out, String component, String key, ValueType type,
                            String defaultValue, boolean defaultPresent) {
        out.add(new Binding(Channel.ENVIRONMENT, component, key, type, defaultValue,
                defaultPresent, false, false, ""));
    }

    private static void secretEnv(List<Binding> out, String component, String key,
                                  boolean defaultPresent) {
        out.add(new Binding(Channel.ENVIRONMENT, component, key, ValueType.STRING, REDACTED,
                defaultPresent, true, false, ""));
    }

    private static Map<Identity, Binding> index(List<Binding> entries) {
        Map<Identity, Binding> index = new LinkedHashMap<>();
        for (Binding binding : entries) {
            Binding previous = index.put(binding.identity(), binding);
            if (previous != null) {
                throw new IllegalStateException("Duplicate public configuration binding: "
                        + binding.identity());
            }
        }
        return Collections.unmodifiableMap(index);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
