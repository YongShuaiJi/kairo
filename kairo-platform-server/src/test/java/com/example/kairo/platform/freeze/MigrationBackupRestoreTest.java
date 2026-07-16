package com.example.kairo.platform.freeze;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the documented backup/restore rollback boundary using a real pre-upgrade database. */
class MigrationBackupRestoreTest {

    @Test
    void v101BackupCanBeRestoredAndUpgradedAgainWithoutDataLoss() throws Exception {
        DataSource original = MigrationForwardCompatTest.dataSource("backup_original");
        MigrationForwardCompatTest.flyway(original, 34).migrate();
        MigrationForwardCompatTest.seedBusinessRows(original, "backup");

        Path backup = Files.createTempFile("kairo-v101-backup-", ".sql");
        try {
            try (Connection connection = original.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("SCRIPT TO '" + sqlPath(backup) + "'");
            }
            assertThat(Files.size(backup)).isGreaterThan(1_000L);

            Flyway upgradedOriginal = MigrationForwardCompatTest.flyway(original, null);
            upgradedOriginal.migrate();
            assertThat(upgradedOriginal.validateWithResult().validationSuccessful).isTrue();
            MigrationForwardCompatTest.assertBusinessRows(original, "backup");

            DataSource restored = restoredDataSource();
            try (Connection connection = restored.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("RUNSCRIPT FROM '" + sqlPath(backup) + "'");
            }
            MigrationForwardCompatTest.assertBusinessRows(restored, "backup");
            Flyway upgradedRestored = MigrationForwardCompatTest.flyway(restored, null);
            upgradedRestored.migrate();
            assertThat(upgradedRestored.validateWithResult().validationSuccessful).isTrue();
            MigrationForwardCompatTest.assertBusinessRows(restored, "backup");
            assertThat(upgradedRestored.migrate().migrationsExecuted).isZero();
        } finally {
            Files.deleteIfExists(backup);
        }
    }

    private static DataSource restoredDataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:kairo_v17_restored_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static String sqlPath(Path path) {
        return path.toAbsolutePath().toString().replace("'", "''");
    }
}
