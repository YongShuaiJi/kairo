package com.example.kairo.platform.freeze;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M0 / &sect;3.2: the database-migration freeze gate. Compare-only -- it never writes the
 * baseline. It recomputes each migration's SHA-256 (over the committed file) and Flyway's own
 * checksum (from an empty-DB migration) and asserts every frozen migration is byte-identical to
 * the V1.6.0 baseline ({@code 113823b}). Modification / removal fails the build; additive new
 * migrations (V42+) are allowed and reported.
 */
class MigrationFreezeTest {

    private static final String BASELINE = "v1.7/migration-v1-hashes.json";

    @Test
    void frozenMigrationsAreImmutable() throws Exception {
        List<FreezeModels.FrozenMigration> current = FreezeCollectors.collectMigrations().migrations();
        FreezeModels.FrozenMigrations baseline =
                FreezeBaselineSupport.readBaseline(BASELINE, FreezeModels.FrozenMigrations.class);
        TreeMap<String, FreezeModels.FrozenMigration> byFile = new TreeMap<>();
        for (FreezeModels.FrozenMigration m : current) {
            byFile.put(m.file(), m);
        }

        List<String> violations = new ArrayList<>();
        for (FreezeModels.FrozenMigration expected : baseline.migrations()) {
            FreezeModels.FrozenMigration actual = byFile.get(expected.file());
            if (actual == null) {
                violations.add("FROZEN MIGRATION REMOVED: " + expected.file()
                        + " (removing a released migration breaks existing databases)");
                continue;
            }
            if (!actual.sha256().equals(expected.sha256())) {
                violations.add("FROZEN MIGRATION MODIFIED: " + expected.file()
                        + " sha256 " + expected.sha256() + " -> " + actual.sha256()
                        + "; editing a released migration is a breaking change."
                        + " Add a new V" + (Integer.parseInt(expected.version()) + 1) + "+ migration instead.");
            }
            if (actual.flywayChecksum() != expected.flywayChecksum()) {
                violations.add("FROZEN MIGRATION Flyway CHECKSUM CHANGED: " + expected.file()
                        + " flywayChecksum " + expected.flywayChecksum() + " -> " + actual.flywayChecksum()
                        + " (Flyway validate rejects this on every existing database)");
            }
        }
        List<String> additions = new ArrayList<>();
        for (FreezeModels.FrozenMigration m : current) {
            if (baseline.migrations().stream().noneMatch(b -> b.file().equals(m.file()))) {
                additions.add(m.file());
            }
        }
        if (!additions.isEmpty()) {
            System.out.println("[freeze] new migrations since baseline (additive, allowed): " + additions);
        }
        assertThat(violations)
                .as("Frozen Flyway migrations (V1.6.0 / 113823b) must not be modified or removed "
                        + "(breaking change).")
                .isEmpty();
    }
}
