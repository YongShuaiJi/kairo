package com.example.kairo.compatmatrix;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic catalog tests: the single frozen C01-C10 catalog (section 10.1) and
 * the section 10.2 non-formal exclusions. These pin the exact formal/experimental
 * support level, OS/arch, JDK, load mode, fixture and required behavior so no later
 * work package can silently re-point a row. M3-A never asserts any row PASSED here;
 * the catalog only declares expectations.
 */
class CompatibilityScenarioCatalogTest {

    private static final String BUILD = "0123456789abcdef0123456789abcdef01234567";

    /** Expected section 10.1 transcription per scenario id. */
    private record Expected(String id, CompatibilitySupportLevel supportLevel,
                            String runnerOs, String runnerArch, List<Integer> targetJdks,
                            String loadModeRaw, String fixture, String requiredBehaviorsRaw,
                            String workPackage) {
    }

    private static final List<Expected> EXPECTED = List.of(
            new Expected("C01", CompatibilitySupportLevel.FORMAL, "Linux", "x86_64", List.of(17),
                    "premain", "plain Java", "增强、调用、更新、卸载", "M3-B"),
            new Expected("C02", CompatibilitySupportLevel.FORMAL, "Linux", "x86_64", List.of(17),
                    "external attach/agentmain", "plain Java", "attach、增强、卸载、shutdown", "M3-B"),
            new Expected("C03", CompatibilitySupportLevel.FORMAL, "Linux", "x86_64", List.of(21),
                    "premain", "Spring Boot 3 executable jar", "注册、发布、调用、卸载", "M3-C"),
            new Expected("C04", CompatibilitySupportLevel.FORMAL, "Linux", "x86_64", List.of(21),
                    "external attach", "Spring Boot 3 executable jar", "attach、发布、卸载", "M3-C"),
            new Expected("C05", CompatibilitySupportLevel.FORMAL, "Linux", "x86_64", List.of(21),
                    "premain", "parent/child same-name loaders", "只增强指定 loader", "M3-D"),
            new Expected("C06", CompatibilitySupportLevel.FORMAL, "Linux", "x86_64", List.of(21),
                    "premain", "JDK Proxy/CGLIB/Byte Buddy", "目标解析与精确卸载", "M3-D"),
            new Expected("C07", CompatibilitySupportLevel.FORMAL, "Linux", "x86_64", List.of(17, 21),
                    "premain", "Lambda/bridge/synthetic", "发现、策略、实际行为", "M3-D"),
            new Expected("C08", CompatibilitySupportLevel.FORMAL, "Linux", "x86_64", List.of(21),
                    "premain", "redefine/retransform/hot update", "成功对账或明确 TARGET_DRIFTED", "M3-E"),
            new Expected("C09", CompatibilitySupportLevel.EXPERIMENTAL, "macOS", "arm64", List.of(21),
                    "agentmain", "plain Java", "真实 attach、增强、卸载", "M3-B"),
            new Expected("C10", CompatibilitySupportLevel.FORMAL, "Linux", "x86_64", List.of(21),
                    "premain", "与一个受控 Byte Buddy Agent 共存", "Kairo 卸载不破坏对方变换", "M3-E")
    );

    @Test
    void catalogHasExactlyTenFormalAndExperimentalRowsInOrder() {
        assertThat(CompatibilityScenarioCatalog.all())
                .map(CompatibilityScenario::id)
                .containsExactly("C01", "C02", "C03", "C04", "C05", "C06", "C07", "C08", "C09", "C10");
        assertThat(CompatibilityScenarioCatalog.formalScenarios())
                .map(CompatibilityScenario::id)
                .containsExactly("C01", "C02", "C03", "C04", "C05", "C06", "C07", "C08", "C10");
        assertThat(CompatibilityScenarioCatalog.experimentalScenarios())
                .map(CompatibilityScenario::id)
                .containsExactly("C09");
    }

    @Test
    void everyRowMatchesSectionTenPointOneVerbatim() {
        Map<String, CompatibilityScenario> byId = CompatibilityScenarioCatalog.all().stream()
                .collect(Collectors.toMap(CompatibilityScenario::id, s -> s));
        for (Expected e : EXPECTED) {
            CompatibilityScenario s = byId.get(e.id());
            assertThat(s).as("scenario present: " + e.id()).isNotNull();
            assertThat(s.supportLevel()).as(e.id() + " supportLevel").isEqualTo(e.supportLevel);
            assertThat(s.runnerOs()).as(e.id() + " runnerOs").isEqualTo(e.runnerOs);
            assertThat(s.runnerArch()).as(e.id() + " runnerArch").isEqualTo(e.runnerArch);
            assertThat(s.targetJdks()).as(e.id() + " targetJdks").isEqualTo(e.targetJdks);
            assertThat(s.loadModeRaw()).as(e.id() + " loadModeRaw").isEqualTo(e.loadModeRaw);
            assertThat(s.fixture()).as(e.id() + " fixture").isEqualTo(e.fixture);
            assertThat(s.requiredBehaviorsRaw()).as(e.id() + " requiredBehaviorsRaw").isEqualTo(e.requiredBehaviorsRaw);
            assertThat(s.workPackage()).as(e.id() + " workPackage").isEqualTo(e.workPackage);
            // requiredBehaviors is the strict 、-tokenization of the raw cell.
            List<String> tokens = e.requiredBehaviorsRaw.isEmpty()
                    ? List.of() : List.of(e.requiredBehaviorsRaw.split("、"));
            assertThat(s.requiredBehaviors()).as(e.id() + " requiredBehaviors").isEqualTo(tokens);
            // loadMode enum must round-trip to the raw cell.
            assertThat(s.loadMode().raw()).as(e.id() + " loadMode.raw").isEqualTo(e.loadModeRaw);
        }
    }

    @Test
    void loadModeEnumCoversAllRawCells() {
        Set<String> raws = CompatibilityScenarioCatalog.all().stream()
                .map(s -> s.loadModeRaw()).collect(Collectors.toSet());
        assertThat(raws).containsExactlyInAnyOrder("premain", "external attach/agentmain",
                "external attach", "agentmain");
    }

    @Test
    void versionsAreFixed() {
        assertThat(CompatibilityScenarioCatalog.SCHEMA_VERSION).isEqualTo("1.0");
        assertThat(CompatibilityScenarioCatalog.CATALOG_VERSION).isEqualTo("v1.7-1.0");
    }

    @Test
    void knownScenarioLookupIsExact() {
        for (String id : List.of("C01", "C05", "C09", "C10")) {
            assertThat(CompatibilityScenarioCatalog.isKnownScenario(id)).isTrue();
            assertThat(CompatibilityScenarioCatalog.scenario(id).id()).isEqualTo(id);
        }
        assertThat(CompatibilityScenarioCatalog.isKnownScenario("C11")).isFalse();
        assertThat(CompatibilityScenarioCatalog.isKnownScenario("c01")).isFalse();
        assertThat(CompatibilityScenarioCatalog.isKnownScenario(null)).isFalse();
        assertThat(CompatibilityScenarioCatalog.scenario("C11")).isNull();
    }

    @Test
    void nonFormalExclusionsMatchSectionTenPointTwo() {
        Map<String, String> excl = CompatibilityScenarioCatalog.nonFormalExclusions().stream()
                .collect(Collectors.toMap(
                        CompatibilityScenarioCatalog.NonFormalExclusion::combination,
                        CompatibilityScenarioCatalog.NonFormalExclusion::status));
        assertThat(excl).containsEntry("JDK 8/11 目标 JVM", "NOT_SUPPORTED");
        assertThat(excl).containsEntry("Windows", "EXPERIMENTAL");
        assertThat(excl).containsEntry("Spring Boot 2", "EXPERIMENTAL");
        assertThat(excl).containsEntry("Kotlin 默认参数/协程", "EXPERIMENTAL");
        assertThat(excl).containsEntry("Tomcat 独立部署", "EXPERIMENTAL");
        assertThat(excl).containsEntry("多 Platform 节点", "NOT_SUPPORTED");
    }

    @Test
    void catalogDeclaresNoStatusItIsNotEvidence() {
        // The catalog is a declaration surface, not row evidence; it carries no PASSED
        // field and no scenario is marked passed. M3-A never marks a row PASSED. C09 is
        // the single experimental exception; every other row is a formal commitment.
        for (CompatibilityScenario s : CompatibilityScenarioCatalog.all()) {
            boolean experimental = "C09".equals(s.id());
            assertThat(s.supportLevel() == CompatibilitySupportLevel.EXPERIMENTAL)
                    .as(s.id() + " experimental flag").isEqualTo(experimental);
            assertThat(s.isFormal()).as(s.id() + " formality").isEqualTo(!experimental);
        }
        assertThat(BUILD).hasSize(40);
    }
}
