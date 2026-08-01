package com.example.kairo.compatmatrix;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single frozen V1.7 real-process compatibility-matrix catalog (&sect;10.1),
 * with the &sect;10.2 non-formal exclusions retained for reference/completeness.
 *
 * <p>This is the <strong>only</strong> catalog for C01&ndash;C10. M3-B&hellip;M3-E
 * reuse this catalog, schema and aggregator (&sect;10.4); no second ad-hoc
 * scenario list is permitted. The catalog is immutable and self-contained: it
 * never reads a file and never depends on the V1.5 in-process matrix types.
 *
 * <p>M3-A never marks any row PASSED; the catalog only declares expectations.
 */
public final class CompatibilityScenarioCatalog {

    /** Frozen catalog version, recorded in every row and the aggregate result. */
    public static final String CATALOG_VERSION = "v1.7-1.0";

    /** Row-evidence schema version, recorded in every row and the aggregate result. */
    public static final String SCHEMA_VERSION = "1.0";

    private static final List<CompatibilityScenario> SCENARIOS = List.of(
            new CompatibilityScenario("C01", CompatibilitySupportLevel.FORMAL,
                    "Linux", "x86_64", List.of(17),
                    LoadMode.PREMAIN, LoadMode.PREMAIN.raw(),
                    "plain Java",
                    "增强、调用、更新、卸载",
                    List.of("增强", "调用", "更新", "卸载"),
                    "M3-B",
                    "premain on a plain-Java target: enhance, invoke, update and precise unload."),
            new CompatibilityScenario("C02", CompatibilitySupportLevel.FORMAL,
                    "Linux", "x86_64", List.of(17),
                    LoadMode.EXTERNAL_ATTACH_AGENTMAIN, "external attach/agentmain",
                    "plain Java",
                    "attach、增强、卸载、shutdown",
                    List.of("attach", "增强", "卸载", "shutdown"),
                    "M3-B",
                    "external attach/agentmain on a plain-Java target: attach, enhance, unload, shutdown."),
            new CompatibilityScenario("C03", CompatibilitySupportLevel.FORMAL,
                    "Linux", "x86_64", List.of(21),
                    LoadMode.PREMAIN, LoadMode.PREMAIN.raw(),
                    "Spring Boot 3 executable jar",
                    "注册、发布、调用、卸载",
                    List.of("注册", "发布", "调用", "卸载"),
                    "M3-C",
                    "premain on a Spring Boot 3 executable jar: register, publish, invoke, unload."),
            new CompatibilityScenario("C04", CompatibilitySupportLevel.FORMAL,
                    "Linux", "x86_64", List.of(21),
                    LoadMode.EXTERNAL_ATTACH, "external attach",
                    "Spring Boot 3 executable jar",
                    "attach、发布、卸载",
                    List.of("attach", "发布", "卸载"),
                    "M3-C",
                    "external attach on a Spring Boot 3 executable jar: attach, publish, unload."),
            new CompatibilityScenario("C05", CompatibilitySupportLevel.FORMAL,
                    "Linux", "x86_64", List.of(21),
                    LoadMode.PREMAIN, LoadMode.PREMAIN.raw(),
                    "parent/child same-name loaders",
                    "只增强指定 loader",
                    List.of("只增强指定 loader"),
                    "M3-D",
                    "premain on parent/child same-name loaders: only the designated loader is enhanced."),
            new CompatibilityScenario("C06", CompatibilitySupportLevel.FORMAL,
                    "Linux", "x86_64", List.of(21),
                    LoadMode.PREMAIN, LoadMode.PREMAIN.raw(),
                    "JDK Proxy/CGLIB/Byte Buddy",
                    "目标解析与精确卸载",
                    List.of("目标解析与精确卸载"),
                    "M3-D",
                    "premain on JDK Proxy/CGLIB/Byte Buddy: target resolution and precise unload."),
            new CompatibilityScenario("C07", CompatibilitySupportLevel.FORMAL,
                    "Linux", "x86_64", List.of(17, 21),
                    LoadMode.PREMAIN, LoadMode.PREMAIN.raw(),
                    "Lambda/bridge/synthetic",
                    "发现、策略、实际行为",
                    List.of("发现", "策略", "实际行为"),
                    "M3-D",
                    "premain on Lambda/bridge/synthetic: discovery, policy and actual behavior (JDK 17 and 21)."),
            new CompatibilityScenario("C08", CompatibilitySupportLevel.FORMAL,
                    "Linux", "x86_64", List.of(21),
                    LoadMode.PREMAIN, LoadMode.PREMAIN.raw(),
                    "redefine/retransform/hot update",
                    "成功对账或明确 TARGET_DRIFTED",
                    List.of("成功对账或明确 TARGET_DRIFTED"),
                    "M3-E",
                    "premain on redefine/retransform/hot update: safe reconciliation or explicit TARGET_DRIFTED."),
            new CompatibilityScenario("C09", CompatibilitySupportLevel.EXPERIMENTAL,
                    "macOS", "arm64", List.of(21),
                    LoadMode.AGENTMAIN, LoadMode.AGENTMAIN.raw(),
                    "plain Java",
                    "真实 attach、增强、卸载",
                    List.of("真实 attach", "增强", "卸载"),
                    "M3-B",
                    "agentmain on a plain-Java target on macOS arm64: real attach, enhance, unload. "
                            + "Without a real macOS CI the final status is EXPERIMENTAL and macOS is not formal support."),
            new CompatibilityScenario("C10", CompatibilitySupportLevel.FORMAL,
                    "Linux", "x86_64", List.of(21),
                    LoadMode.PREMAIN, LoadMode.PREMAIN.raw(),
                    "与一个受控 Byte Buddy Agent 共存",
                    "Kairo 卸载不破坏对方变换",
                    List.of("Kairo 卸载不破坏对方变换"),
                    "M3-E",
                    "premain coexisting with one in-repo fixed Byte Buddy Agent: Kairo unload does not break the other transform.")
    );

    private static final Map<String, CompatibilityScenario> BY_ID =
            SCENARIOS.stream().collect(Collectors.toUnmodifiableMap(CompatibilityScenario::id, Function.identity()));

    private CompatibilityScenarioCatalog() {
    }

    /** All frozen scenarios C01&ndash;C10 in declared order. */
    public static List<CompatibilityScenario> all() {
        return SCENARIOS;
    }

    /** All formal (committed) scenarios. */
    public static List<CompatibilityScenario> formalScenarios() {
        return SCENARIOS.stream().filter(CompatibilityScenario::isFormal).toList();
    }

    /** All experimental (non-blocking) scenarios. */
    public static List<CompatibilityScenario> experimentalScenarios() {
        return SCENARIOS.stream().filter(s -> !s.isFormal()).toList();
    }

    /** The scenario for the given id, or {@code null} if unknown. */
    public static CompatibilityScenario scenario(String id) {
        Objects.requireNonNull(id, "id");
        return BY_ID.get(id);
    }

    /** Whether {@code id} is a known C01&ndash;C10 scenario. */
    public static boolean isKnownScenario(String id) {
        return id != null && BY_ID.containsKey(id);
    }

    /** The &sect;10.2 non-formal exclusions, for reference/completeness only. */
    public static List<NonFormalExclusion> nonFormalExclusions() {
        return List.of(
                new NonFormalExclusion("JDK 8/11 目标 JVM", "NOT_SUPPORTED"),
                new NonFormalExclusion("Windows", "EXPERIMENTAL"),
                new NonFormalExclusion("Spring Boot 2", "EXPERIMENTAL"),
                new NonFormalExclusion("Kotlin 默认参数/协程", "EXPERIMENTAL"),
                new NonFormalExclusion("Tomcat 独立部署", "EXPERIMENTAL"),
                new NonFormalExclusion("多 Platform 节点", "NOT_SUPPORTED")
        );
    }

    /** A &sect;10.2 declared non-formal combination and its status (reference only). */
    public record NonFormalExclusion(String combination, String status) {
        public NonFormalExclusion {
            Objects.requireNonNull(combination, "combination");
            Objects.requireNonNull(status, "status");
        }
    }
}
