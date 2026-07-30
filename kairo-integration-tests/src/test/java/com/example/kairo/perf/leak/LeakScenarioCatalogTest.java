package com.example.kairo.perf.leak;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic tests for {@link LeakScenarioCatalog} distribution and the fixed
 * six-scenario matrix (Byte Buddy generated class added per the &sect;9.3 coverage
 * requirement). No JVM lifecycle; pure allocation logic.
 */
class LeakScenarioCatalogTest {

    @Test
    void matrixHasSixCyclicScenarios() {
        assertThat(LeakScenarioCatalog.all()).hasSize(6);
        assertThat(LeakScenarioCatalog.ids()).containsExactly(
                "business-classloader", "jdk-proxy", "lambda-bridge-synthetic",
                "groovy-compile-cache", "cglib-detection", "bytebuddy-generated");
    }

    @Test
    void everyScenarioDeclaresLeakSurface() {
        for (LeakScenarioCatalog.Scenario s : LeakScenarioCatalog.all()) {
            assertThat(s.leakSurface()).as(s.id() + " leakSurface").isNotBlank();
            assertThat(s.category()).as(s.id() + " category").isNotBlank();
            assertThat(s.description()).as(s.id() + " description").isNotBlank();
        }
    }

    @Test
    void minimumCyclesCoversEveryScenarioOnce() {
        int[] d = LeakScenarioCatalog.distribute(LeakScenarioCatalog.MIN_CYCLES);
        assertThat(d).hasSize(6);
        int sum = 0;
        for (int count : d) {
            assertThat(count).isGreaterThanOrEqualTo(1);
            sum += count;
        }
        assertThat(sum).isEqualTo(LeakScenarioCatalog.MIN_CYCLES);
    }

    @Test
    void distributionFor500SumsTo500() {
        int[] d = LeakScenarioCatalog.distribute(500);
        assertThat(d).hasSize(6);
        int sum = 0;
        for (int count : d) {
            assertThat(count).isGreaterThanOrEqualTo(1);
            sum += count;
        }
        assertThat(sum).isEqualTo(500);
    }

    @Test
    void distributionIsDeterministic() {
        assertThat(LeakScenarioCatalog.distribute(100)).isEqualTo(LeakScenarioCatalog.distribute(100));
    }

    @Test
    void belowMinimumIsRejected() {
        assertThatThrownBy(() -> LeakScenarioCatalog.distribute(LeakScenarioCatalog.MIN_CYCLES - 1))
                .hasMessageContaining(">= " + LeakScenarioCatalog.MIN_CYCLES);
    }
}
