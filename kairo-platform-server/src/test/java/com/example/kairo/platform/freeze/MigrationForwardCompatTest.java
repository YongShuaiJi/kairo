package com.example.kairo.platform.freeze;

import com.fasterxml.jackson.databind.JsonNode;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Upgrades databases at every V1.X milestone to the V1.6/V1.7 migration floor, preserving real
 * hierarchy and user rows. Historical max versions and migration bytes are verified against the
 * actual source commits, so these are lineage-backed fixtures rather than hand-written schemas.
 */
class MigrationForwardCompatTest {

    @Test
    void everyHistoricalV1xDatabaseUpgradesValidatesAndRestartsIdempotently() throws Exception {
        List<Milestone> milestones = milestones();
        verifyHistoricalLineage(milestones);
        for (Milestone milestone : milestones) {
            DataSource dataSource = dataSource(milestone.release());
            Flyway historical = flyway(dataSource, milestone.maxMigration());
            assertThat(historical.migrate().migrationsExecuted)
                    .as(milestone.release() + " historical migration count")
                    .isEqualTo(milestone.maxMigration());
            seedBusinessRows(dataSource, suffix(milestone.release()));

            Flyway current = flyway(dataSource, null);
            current.migrate();
            assertThat(current.validateWithResult().validationSuccessful)
                    .as(milestone.release() + " Flyway validate after upgrade").isTrue();
            assertThat(current.info().current().getVersion())
                    .isEqualTo(MigrationVersion.fromVersion("43"));
            assertBusinessRows(dataSource, suffix(milestone.release()));

            assertThat(current.migrate().migrationsExecuted)
                    .as(milestone.release() + " repeated startup must be idempotent")
                    .isZero();
            assertBusinessRows(dataSource, suffix(milestone.release()));
        }
    }

    private static void verifyHistoricalLineage(List<Milestone> milestones) throws Exception {
        FreezeModels.FrozenMigrations frozen = FreezeBaselineSupport.readBaseline(
                "v1.7/migration-v1-hashes.json", FreezeModels.FrozenMigrations.class);
        Map<String, FreezeModels.FrozenMigration> byFile = new TreeMap<>();
        frozen.migrations().forEach(migration -> byFile.put(migration.file(), migration));

        for (Milestone milestone : milestones) {
            List<String> files = gitLines("ls-tree", "-r", "--name-only", milestone.sourceCommit(),
                    "--", "kairo-platform-server/src/main/resources/db/migration");
            int maximum = files.stream().map(MigrationForwardCompatTest::migrationVersion)
                    .max(Integer::compareTo).orElseThrow();
            assertThat(maximum).as(milestone.release() + " source max migration")
                    .isEqualTo(milestone.maxMigration());
            for (String file : files) {
                String name = Path.of(file).getFileName().toString();
                byte[] historicalBytes = gitBytes("show", milestone.sourceCommit() + ":" + file);
                assertThat(byFile).containsKey(name);
                assertThat(sha256(historicalBytes))
                        .as(milestone.release() + " immutable migration " + name)
                        .isEqualTo(byFile.get(name).sha256());
            }
        }
    }

    static Flyway flyway(DataSource dataSource, Integer target) {
        var configuration = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(String.valueOf(target)));
        }
        return configuration.load();
    }

    static DataSource dataSource(String id) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:kairo_v17_migration_" + suffix(id)
                + "_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    static void seedBusinessRows(DataSource dataSource, String suffix) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            insert(connection, "insert into enterprise(id,name) values(?,?)",
                    "ent-upgrade-" + suffix, "Upgrade Enterprise " + suffix);
            insert(connection, "insert into organization(id,enterprise_id,name) values(?,?,?)",
                    "org-upgrade-" + suffix, "ent-upgrade-" + suffix, "Upgrade Org " + suffix);
            insert(connection, "insert into project(id,organization_id,name) values(?,?,?)",
                    "proj-upgrade-" + suffix, "org-upgrade-" + suffix, "Upgrade Project " + suffix);
            insert(connection, "insert into application(id,project_id,name) values(?,?,?)",
                    "app-upgrade-" + suffix, "proj-upgrade-" + suffix, "Upgrade App " + suffix);
            insert(connection, "insert into environment(id,application_id,name,type) values(?,?,?,?)",
                    "env-upgrade-" + suffix, "app-upgrade-" + suffix, "dev", "dev");
            insert(connection,
                    "insert into user_account(id,username,display_name,status) values(?,?,?,?)",
                    "user-upgrade-" + suffix, "upgrade-" + suffix,
                    "Upgrade User " + suffix, "ACTIVE");
        }
    }

    static void assertBusinessRows(DataSource dataSource, String suffix) throws Exception {
        assertThat(count(dataSource, "enterprise", "ent-upgrade-" + suffix)).isOne();
        assertThat(count(dataSource, "organization", "org-upgrade-" + suffix)).isOne();
        assertThat(count(dataSource, "project", "proj-upgrade-" + suffix)).isOne();
        assertThat(count(dataSource, "application", "app-upgrade-" + suffix)).isOne();
        assertThat(count(dataSource, "environment", "env-upgrade-" + suffix)).isOne();
        assertThat(count(dataSource, "user_account", "user-upgrade-" + suffix)).isOne();
    }

    private static void insert(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            assertThat(statement.executeUpdate()).isOne();
        }
    }

    private static int count(DataSource dataSource, String table, String id) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select count(*) from " + table + " where id=?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static List<Milestone> milestones() throws Exception {
        try (InputStream in = MigrationForwardCompatTest.class.getClassLoader()
                .getResourceAsStream("v1.7/fixtures/historical-db-milestones.json")) {
            if (in == null) throw new IllegalStateException("historical DB milestone fixture missing");
            JsonNode root = FreezeBaselineSupport.mapper().readTree(in);
            List<Milestone> result = new ArrayList<>();
            for (JsonNode node : root.path("milestones")) {
                result.add(new Milestone(node.path("release").asText(),
                        node.path("sourceCommit").asText(), node.path("maxMigration").asInt()));
            }
            return result;
        }
    }

    private static int migrationVersion(String path) {
        String name = Path.of(path).getFileName().toString();
        return Integer.parseInt(name.substring(1, name.indexOf('_')));
    }

    private static String suffix(String value) {
        return value.toLowerCase().replaceAll("[^a-z0-9]+", "_");
    }

    private static List<String> gitLines(String... args) throws Exception {
        return new String(gitBytes(args), StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank()).toList();
    }

    private static byte[] gitBytes(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(repositoryRoot().toFile())
                .redirectErrorStream(false).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        process.getErrorStream().transferTo(error);
        int exit = process.waitFor();
        assertThat(exit).as(String.join(" ", command) + ": " + error).isZero();
        return output.toByteArray();
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve("kairo-platform-server"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Milestone(String release, String sourceCommit, int maxMigration) {
    }
}
