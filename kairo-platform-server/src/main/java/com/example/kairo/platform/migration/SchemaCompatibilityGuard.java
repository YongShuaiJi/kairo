package com.example.kairo.platform.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.stereotype.Component;

/**
 * V1.7 M1-F &sect;8.6 items 5/6: application rollback protection. Flyway is the authoritative schema
 * ledger; Kairo never performs automatic down migrations (Flyway Community has no undo, and clean is
 * disabled in {@code application.yml}). When the database carries an applied migration that the
 * running build cannot resolve -- the signature of an application rolled back to an older build while
 * the database was already migrated by a newer one -- startup is refused with a structured message
 * that points operators at restoring from the pre-upgrade backup rather than downgrading the schema.
 *
 * <p>The guard wraps Spring Boot's {@link FlywayMigrationStrategy#migrate}: a {@code validate} failure
 * inside {@code Flyway.migrate()} is probed and, if it confirms the rollback shape (an applied
 * migration is no longer resolvable), re-raised as the structured recovery error. After a successful
 * migrate the same probe runs again so a rollback-shaped history can never boot the application.
 *
 * <p>The single-Platform support boundary and the V1~V43 migration floor are preserved: no migration
 * files are added, removed or re-ordered, and no {@code clean}/{@code undo} is ever invoked.
 */
@Component
public class SchemaCompatibilityGuard implements FlywayMigrationStrategy {

    /**
     * The operator guidance emitted on a rollback-incompatible schema. Always says "restore from the
     * pre-upgrade backup"; never "downgrade the schema".
     */
    static final String RECOVERY_GUIDANCE =
            "Kairo refused to start because the database schema is newer than this build's migrations "
                    + "(an application rollback to an older build against an already-migrated database). "
                    + "Do not downgrade the schema. Restore the database from the pre-upgrade backup taken "
                    + "before the 1.6 -> 1.7 upgrade, then restart this build against the restored schema.";

    @Override
    public void migrate(Flyway flyway) {
        try {
            flyway.migrate();
        } catch (FlywayException e) {
            // A validate/migrate failure may itself be the rollback signal (an applied migration is
            // no longer resolvable). Probe the history; if it confirms the rollback shape, fail with
            // the structured recovery message instead of a raw Flyway error.
            MigrationInfo offending = firstUnresolvedAppliedMigration(flyway);
            if (offending != null) {
                throw new IllegalStateException(describeIncompatible(offending), e);
            }
            throw e;
        }
        verifyCompatible(flyway);
    }

    /**
     * Post-migrate probe: refuse startup if the history carries an applied migration the code cannot
     * resolve. Public so tests and a future explicit health check can invoke it directly.
     */
    public void verifyCompatible(Flyway flyway) {
        MigrationInfo offending = firstUnresolvedAppliedMigration(flyway);
        if (offending != null) {
            throw new IllegalStateException(describeIncompatible(offending));
        }
    }

    /**
     * @return the first successful or failed applied migration that this build cannot resolve.
     *         Flyway reports this rollback shape as either {@code MISSING_*} or {@code FUTURE_*},
     *         depending on its version and the position of the absent migration.
     */
    private static MigrationInfo firstUnresolvedAppliedMigration(Flyway flyway) {
        MigrationInfo[] all;
        try {
            all = flyway.info().all();
        } catch (RuntimeException ignored) {
            // Cannot inspect history; defer to Flyway's own error path.
            return null;
        }
        for (MigrationInfo info : all) {
            if (info == null) {
                continue;
            }
            MigrationState state = info.getState();
            if (state == MigrationState.MISSING_SUCCESS
                    || state == MigrationState.MISSING_FAILED
                    || state == MigrationState.FUTURE_SUCCESS
                    || state == MigrationState.FUTURE_FAILED) {
                return info;
            }
        }
        return null;
    }

    private static String describeIncompatible(MigrationInfo info) {
        // getVersion() can be null for a history-only MISSING row; fall back to the script name,
        // which always carries the version (e.g. V99__rollback_sim.sql) so the operator can identify it.
        String id = info.getVersion() != null ? "V" + info.getVersion() : String.valueOf(info.getScript());
        return "Schema rollback guard: applied migration " + id
                + " ('" + info.getDescription() + "', state=" + info.getState()
                + ") is not resolvable by this build. " + RECOVERY_GUIDANCE;
    }
}
