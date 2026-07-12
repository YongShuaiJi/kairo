package com.example.kairo.platform.script;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.ScriptPolicyRevision;
import com.example.kairo.platform.persistence.mapper.TestPlatformMapper;
import com.example.kairo.platform.service.RequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ScriptCapabilityPolicyIntegrationTest {

    @Autowired ScriptCapabilityPolicyService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestPlatformMapper fixtures;

    private RequestContext admin;

    @BeforeEach
    void setUp() {
        fixtures.ensureDefaultProject();
        fixtures.ensureDefaultApplication();
        fixtures.ensureDefaultEnvironment();
        admin = new RequestContext("system", "corr", "127.0.0.1", "header-dev", "test");
    }

    @Test
    void platformCeilingIsSeededUnrestrictedAndAppDefaultsToUnrestricted() {
        assertThat(service.platformMax()).isEqualTo(CapabilityProfile.UNRESTRICTED);
        assertThat(service.applicationMax("app-default")).isEqualTo(CapabilityProfile.UNRESTRICTED);
        assertThat(service.effective("app-default", CapabilityProfile.EXTENDED))
                .isEqualTo(CapabilityProfile.EXTENDED);
    }

    @Test
    void describeReportsEffectiveTierAndDefaultsWhenNoAppPolicy() {
        Map<String, Object> description = service.describe("app-default");
        assertThat(description).satisfies(d -> {
            assertThat(d.get("hasApplicationPolicy")).isEqualTo(false);
            assertThat(d.get("platformMaxProfile")).isEqualTo("UNRESTRICTED");
            assertThat(d.get("applicationMaxProfile")).isEqualTo("UNRESTRICTED");
            assertThat(d.get("effectiveMaxProfile")).isEqualTo("UNRESTRICTED");
            assertThat(d.get("revision")).isEqualTo(0L);
        });
    }

    @Test
    void putCreatesApplicationPolicyAtRevisionOneAndBumpsOnUpdate() {
        Map<String, Object> created = service.put(admin, "app-default",
                Map.of("allowedMaxProfile", "EXTENDED"));
        assertThat(created).satisfies(d -> {
            assertThat(d.get("hasApplicationPolicy")).isEqualTo(true);
            assertThat(d.get("applicationMaxProfile")).isEqualTo("EXTENDED");
            assertThat(d.get("revision")).isEqualTo(1L);
        });

        Map<String, Object> updated = service.put(admin, "app-default",
                Map.of("allowedMaxProfile", "SAFE", "expectedRevision", 1L));
        assertThat(updated.get("revision")).isEqualTo(2L);
        assertThat(updated.get("applicationMaxProfile")).isEqualTo("SAFE");
        assertThat(service.effective("app-default", CapabilityProfile.UNRESTRICTED))
                .isEqualTo(CapabilityProfile.SAFE);
    }

    @Test
    void effectiveTierIsMostRestrictiveAcrossPlatformAppAndRequest() {
        service.put(admin, "app-default", Map.of("allowedMaxProfile", "EXTENDED"));
        // min(UNRESTRICTED platform, EXTENDED app, UNRESTRICTED request) = EXTENDED
        assertThat(service.effective("app-default", CapabilityProfile.UNRESTRICTED))
                .isEqualTo(CapabilityProfile.EXTENDED);
        // min(UNRESTRICTED, EXTENDED, SAFE) = SAFE
        assertThat(service.effective("app-default", CapabilityProfile.SAFE))
                .isEqualTo(CapabilityProfile.SAFE);
    }

    @Test
    void putRejectsStaleRevisionWithConflict() {
        service.put(admin, "app-default", Map.of("allowedMaxProfile", "EXTENDED"));
        assertThatThrownBy(() -> service.put(admin, "app-default",
                Map.of("allowedMaxProfile", "SAFE", "expectedRevision", 99L)))
                .isInstanceOfSatisfying(com.example.kairo.platform.service.PlatformException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo("RESOURCE_VERSION_CONFLICT");
                });
        // The policy is unchanged after the rejected update.
        assertThat(service.applicationMax("app-default")).isEqualTo(CapabilityProfile.EXTENDED);
    }

    @Test
    void putRequiresExistingRevisionToUpdate() {
        service.put(admin, "app-default", Map.of("allowedMaxProfile", "EXTENDED"));
        assertThatThrownBy(() -> service.put(admin, "app-default",
                Map.of("allowedMaxProfile", "SAFE"))) // no expectedRevision
                .isInstanceOf(com.example.kairo.platform.service.PlatformException.class);
    }

    @Test
    void putRejectsInvalidProfile() {
        assertThatThrownBy(() -> service.put(admin, "app-default",
                Map.of("allowedMaxProfile", "LUDICROUS")))
                .isInstanceOf(com.example.kairo.platform.service.PlatformException.class);
    }

    @Test
    void revisionToPinChangesWhenAppPolicyChanges() {
        ScriptPolicyRevision before = service.revisionToPin("app-default");
        service.put(admin, "app-default", Map.of("allowedMaxProfile", "SAFE"));
        ScriptPolicyRevision after = service.revisionToPin("app-default");
        assertThat(after.revision()).isGreaterThan(before.revision());
        assertThat(after.hash()).isNotEqualTo(before.hash());
    }

    @Test
    void platformRowExistsInDatabaseAfterMigration() {
        Map<String, Object> row = jdbc.queryForMap(
                "select scope, application_id, allowed_max_profile, revision from script_capability_policy "
                        + "where scope = 'PLATFORM'");
        assertThat(row).satisfies(r -> {
            assertThat(r.get("SCOPE")).isEqualTo("PLATFORM");
            assertThat(r.get("APPLICATION_ID")).isEqualTo("__platform__");
            assertThat(r.get("ALLOWED_MAX_PROFILE")).isEqualTo("UNRESTRICTED");
            assertThat(((Number) r.get("REVISION")).longValue()).isZero();
        });
    }
}
