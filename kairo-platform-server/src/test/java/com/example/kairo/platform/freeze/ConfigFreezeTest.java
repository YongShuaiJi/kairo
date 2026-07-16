package com.example.kairo.platform.freeze;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M0 / &sect;3.3: the configuration freeze gate. Compare-only. Reads the authoritative
 * production {@code KairoConfigCatalog} and asserts every frozen binding is unchanged:
 * channel, component, key, type, non-secret default, sensitivity, default presence and deprecation
 * metadata. Removal / rename / semantic change fails the build; additive bindings are allowed.
 */
class ConfigFreezeTest {

    private static final String BASELINE = "v1.7/config-v1-frozen.json";

    @Test
    void frozenConfigIsUnchanged() throws Exception {
        FreezeModels.FrozenConfig current = FreezeCollectors.collectConfig();
        FreezeModels.FrozenConfig baseline =
                FreezeBaselineSupport.readBaseline(BASELINE, FreezeModels.FrozenConfig.class);

        TreeMap<String, FreezeModels.ConfigKey> currentKeys = new TreeMap<>();
        for (FreezeModels.ConfigKey k : current.configKeys()) {
            currentKeys.put(id(k.component(), k.key()), k);
        }
        TreeMap<String, FreezeModels.EnvVar> currentEnv = new TreeMap<>();
        for (FreezeModels.EnvVar e : current.envVars()) {
            currentEnv.put(id(e.component(), e.key()), e);
        }

        List<String> violations = new ArrayList<>();
        for (FreezeModels.ConfigKey expected : baseline.configKeys()) {
            FreezeModels.ConfigKey actual = currentKeys.get(id(expected.component(), expected.key()));
            if (actual == null) {
                violations.add("FROZEN CONFIG BINDING REMOVED: "
                        + id(expected.component(), expected.key()));
                continue;
            }
            compare(violations, "config:" + expected.key(), expected, actual);
        }
        for (FreezeModels.EnvVar expected : baseline.envVars()) {
            FreezeModels.EnvVar actual = currentEnv.get(id(expected.component(), expected.key()));
            if (actual == null) {
                violations.add("FROZEN ENV BINDING REMOVED: "
                        + id(expected.component(), expected.key()));
                continue;
            }
            compare(violations, "env:" + expected.key(), expected, actual);
        }
        reportAdditions("config keys",
                baseline.configKeys().stream().map(k -> id(k.component(), k.key())).toList(),
                current.configKeys().stream().map(k -> id(k.component(), k.key())).toList());
        reportAdditions("env vars",
                baseline.envVars().stream().map(e -> id(e.component(), e.key())).toList(),
                current.envVars().stream().map(e -> id(e.component(), e.key())).toList());

        assertThat(violations)
                .as("Frozen config (V1.6.0 / 113823b) must not have key/component/type/default/"
                        + "sensitivity/default-present changes (breaking change).")
                .isEmpty();
    }

    private static void compare(List<String> violations, String label,
                                FreezeModels.ConfigKey expected, FreezeModels.ConfigKey actual) {
        if (!expected.component().equals(actual.component())) {
            violations.add(label + " COMPONENT CHANGED " + expected.component() + " -> " + actual.component());
        }
        if (!expected.type().equals(actual.type())) {
            violations.add(label + " TYPE CHANGED " + expected.type() + " -> " + actual.type());
        }
        if (expected.sensitive() != actual.sensitive()) {
            violations.add(label + " SENSITIVITY CHANGED " + expected.sensitive() + " -> " + actual.sensitive());
        }
        if (expected.defaultPresent() != actual.defaultPresent()) {
            violations.add(label + " DEFAULT-PRESENT CHANGED " + expected.defaultPresent()
                    + " -> " + actual.defaultPresent());
        }
        if (!expected.sensitive() && !expected.defaultValue().equals(actual.defaultValue())) {
            violations.add(label + " DEFAULT CHANGED " + expected.defaultValue() + " -> " + actual.defaultValue());
        }
        compareDeprecation(violations, label, expected.deprecated(), expected.replacement(),
                actual.deprecated(), actual.replacement());
    }

    private static void compare(List<String> violations, String label,
                                FreezeModels.EnvVar expected, FreezeModels.EnvVar actual) {
        if (!expected.type().equals(actual.type())) {
            violations.add(label + " TYPE CHANGED " + expected.type() + " -> " + actual.type());
        }
        if (expected.sensitive() != actual.sensitive()) {
            violations.add(label + " SENSITIVITY CHANGED " + expected.sensitive() + " -> " + actual.sensitive());
        }
        if (expected.defaultPresent() != actual.defaultPresent()) {
            violations.add(label + " DEFAULT-PRESENT CHANGED " + expected.defaultPresent()
                    + " -> " + actual.defaultPresent());
        }
        if (!expected.sensitive() && !expected.defaultValue().equals(actual.defaultValue())) {
            violations.add(label + " DEFAULT CHANGED " + expected.defaultValue() + " -> " + actual.defaultValue());
        }
        compareDeprecation(violations, label, expected.deprecated(), expected.replacement(),
                actual.deprecated(), actual.replacement());
    }

    private static void compareDeprecation(List<String> violations, String label,
                                           boolean expectedDeprecated, String expectedReplacement,
                                           boolean actualDeprecated, String actualReplacement) {
        if (expectedDeprecated != actualDeprecated) {
            violations.add(label + " DEPRECATION CHANGED " + expectedDeprecated + " -> " + actualDeprecated);
        }
        if (!str(expectedReplacement).equals(str(actualReplacement))) {
            violations.add(label + " REPLACEMENT CHANGED " + str(expectedReplacement)
                    + " -> " + str(actualReplacement));
        }
    }

    private static String id(String component, String key) {
        return component + ":" + key;
    }

    private static String str(String value) {
        return value == null ? "" : value;
    }

    private static void reportAdditions(String label, List<String> baseline, List<String> current) {
        List<String> additions = new ArrayList<>();
        for (String k : current) {
            if (!baseline.contains(k)) {
                additions.add(k);
            }
        }
        if (!additions.isEmpty()) {
            System.out.println("[freeze] new " + label + " since baseline (additive, allowed): " + additions);
        }
    }
}
