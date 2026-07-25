package com.example.kairo.platform.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V1.7 M1-F &sect;8.6 items 5/6: application rollback schema compatibility guard. Uses a real H2 +
 * real Flyway against the production {@code db/migration} floor (no mocks): a rollback-shaped
 * database (already migrated to a version newer than this build's migrations) must refuse startup
 * with the structured recovery message, must leave the schema intact (no automatic down migration,
 * no clean), while a compatible database boots normally.
 */
class ApplicationRollbackGuardIntegrationTest {

    private static final String RECOVERY_MARKER = "Restore the database from the pre-upgrade backup";

    private final SchemaCompatibilityGuard guard = new SchemaCompatibilityGuard();

    @Test
    void compatibleSchemaBootsAndValidates() {
        DataSource dataSource = dataSource("compatible");
        Flyway flyway = flyway(dataSource, null);
        guard.migrate(flyway);
        assertThat(flyway.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("43"));
        // Belt-and-suspenders probe passes on a compatible schema.
        guard.verifyCompatible(flyway);
    }

    @Test
    void rollbackToOlderBuildRefusesStartupAndLeavesSchemaIntact() {
        DataSource dataSource = dataSource("rollback");
        Flyway flyway = flyway(dataSource, null);
        // Apply this build's migrations (V1..V43).
        flyway.migrate();

        // Simulate a database previously migrated by a newer build (V99) whose migration script is
        // absent from this rolled-back build: insert an applied V99 row directly. Flyway then sees
        // an applied migration it cannot resolve (MISSING_SUCCESS), exactly the rollback shape.
        seedAppliedMigration(dataSource, "99", "rollback sim");

        List<String> before = appliedScripts(dataSource);
        assertThat(before).anyMatch(s -> s.startsWith("V99__"));

        assertThatThrownBy(() -> guard.migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V99")
                .hasMessageContaining(RECOVERY_MARKER);

        // No automatic down migration / no clean: the schema history is byte-for-byte unchanged
        // (V99 still applied, V1..V43 still applied). The guard must never repair the schema.
        List<String> after = appliedScripts(dataSource);
        assertThat(after).isEqualTo(before);
        assertThat(after).anyMatch(s -> s.startsWith("V99__"));
        assertThat(after).anyMatch(s -> s.startsWith("V43__"));
    }

    @Test
    void cleanIsDisabledSoNoDestructiveSchemaWipeIsPossible() {
        DataSource dataSource = dataSource("clean-guard");
        Flyway flyway = flyway(dataSource, null);
        flyway.migrate();
        // Flyway Community has no undo; clean is disabled in production config. Assert clean is
        // rejected so no down/wipe path exists for the guard or the runtime to trigger.
        assertThatThrownBy(flyway::clean)
                .isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        // Schema history survives the rejected clean.
        assertThat(appliedScripts(dataSource)).isNotEmpty();
    }

    private static Flyway flyway(DataSource dataSource, Integer target) {
        var configuration = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .validateOnMigrate(true);
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(String.valueOf(target)));
        }
        return configuration.load();
    }

    private static DataSource dataSource(String id) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:kairo_v17_rollback_" + id + "_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    /** Insert a row mirroring Flyway's {@code flyway_schema_history} shape for an applied migration. */
    private static void seedAppliedMigration(DataSource dataSource, String version, String description) {
        String script = "V" + version + "__" + description.replace(' ', '_') + ".sql";
        new JdbcTemplate(dataSource).update("""
                insert into flyway_schema_history
                  (installed_rank, description, type, script, checksum, installed_by,
                   installed_on, execution_time, success)
                values (?, ?, 'SQL', ?, ?, 'rollback-test', CURRENT_TIMESTAMP, 0, TRUE)
                """, 1000 + Integer.parseInt(version), description, script, 0);
    }

    private static List<String> appliedScripts(DataSource dataSource) {
        return new JdbcTemplate(dataSource).queryForList(
                "select script from flyway_schema_history where success = TRUE order by installed_rank",
                String.class);
    }
}
