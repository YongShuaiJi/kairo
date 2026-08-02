package com.example.kairo.platform.health;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.ValidateOutput;
import org.flywaydb.core.api.output.ValidateResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * V1.7 M4-A &sect;11.1: the bounded Flyway readiness contributor. Registered under the {@code flyway}
 * contributor name (Spring Boot's {@link org.springframework.boot.actuate.health.HealthContributorNameFactory
 * HealthContributorNameFactory} strips the {@code HealthIndicator} suffix from the bean name, so the bean
 * {@code flywayHealthIndicator} is exposed as the {@code flyway} contributor without colliding with Spring
 * Boot's {@code Flyway} infrastructure bean, which is also named {@code flyway}). The readiness group is a
 * fixed, deterministic set {@code readinessState,db,flyway,redis}.
 *
 * <p>Spring Boot 3.3 ships no auto-validating Flyway health indicator, so {@code management.health.flyway
 * .enabled=false} is a defensive no-op today that keeps a future auto contributor from shadowing this one.
 *
 * <p>Each probe runs a <em>real</em> {@link Flyway#validateWithResult()} against the configured
 * migration locations and data source: applied migrations must match this build's classpath migrations
 * (checksums and resolvability). It performs a real, bounded schema-history read, so a PostgreSQL outage
 * drives readiness DOWN exactly like the {@code db} contributor, and readiness recovers the moment the
 * connection returns. It never mutates or migrates the schema from the health request ({@code validate}
 * is read-only; no {@code migrate}/{@code clean}/{@code undo} is ever invoked).
 *
 * <p>Details are intentionally finite and secret-free: the UP path reports only the validated count; the
 * failure path reports at most the first five offending migration versions/descriptions plus a stable
 * classified error. No JDBC URL, password, token or unbounded exception string is ever emitted.
 *
 * <p>Liveness is intentionally untouched: a Flyway/DB outage must not kill Platform liveness (&sect;11.1).
 */
@Component("flywayHealthIndicator")
public class KairoFlywayHealthIndicator implements HealthIndicator {

    /** Cap on the number of offending migrations reported, so a single probe can never emit unbounded output. */
    private static final int MAX_INVALID_REPORTED = 5;

    private final ObjectProvider<Flyway> flywayProvider;

    public KairoFlywayHealthIndicator(ObjectProvider<Flyway> flywayProvider) {
        this.flywayProvider = flywayProvider;
    }

    @Override
    public Health health() {
        Flyway flyway = flywayProvider.getIfAvailable();
        if (flyway == null) {
            // Readiness promises schema compatibility. Silently treating a missing validator as UP
            // would make disabling/misconfiguring Flyway bypass that contract.
            return Health.down()
                    .withDetail("valid", false)
                    .withDetail("error", "flyway validator unavailable")
                    .build();
        }
        try {
            ValidateResult result = flyway.validateWithResult();
            if (result.validationSuccessful) {
                return Health.up()
                        .withDetail("valid", true)
                        .withDetail("validated", result.validateCount)
                        .build();
            }
            // Validation failure (checksum drift / unresolved applied migration). Finite, secret-free.
            return Health.down()
                    .withDetail("valid", false)
                    .withDetail("invalidMigrations", describeInvalid(result.invalidMigrations))
                    .build();
        } catch (FlywayException e) {
            // The schema history could not be read: typically the database is unreachable (the same outage the
            // db contributor already reports), or the history table is unreadable. Emit only a stable,
            // classified message; never the raw exception (it may embed a JDBC URL or driver internals).
            return Health.down()
                    .withDetail("database", "unavailable")
                    .withDetail("error", "flyway validate could not read schema history")
                    .build();
        }
    }

    private static List<String> describeInvalid(List<ValidateOutput> invalid) {
        List<String> out = new ArrayList<>();
        if (invalid == null) {
            return out;
        }
        int count = 0;
        for (ValidateOutput migration : invalid) {
            if (migration == null || count >= MAX_INVALID_REPORTED) {
                break;
            }
            // Version + description only; never filepath (a filesystem path) or the full error message.
            out.add(migration.version + " " + migration.description);
            count++;
        }
        if (invalid.size() > MAX_INVALID_REPORTED) {
            out.add("... (" + (invalid.size() - MAX_INVALID_REPORTED) + " more)");
        }
        return out;
    }
}
