package com.example.kairo.platform.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M4-A &sect;11.1: {@code /actuator/info} exposes exactly the useful, secret-free build-identity
 * fields (version, git commit, build time, Platform Java target 21, contract baseline V1.6.0). The auto
 * build/git/env/java/os info contributors are disabled, so the output is the single bounded object
 * produced by {@link KairoBuildInfoContributor}. Standard {@link BuildProperties}/{@link GitProperties}
 * are preferred when the build generates them, with deterministic documented fallbacks for IDE runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_info;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "kairo.platform.rollout.scheduler.enabled=false",
        "kairo.platform.reconciliation.scheduler.enabled=false",
        "kairo.platform.runtime-lease.initial-delay-ms=999999",
        "kairo.platform.runtime-lease.fixed-delay-ms=999999",
        "kairo.platform.runtime-cleanup.initial-delay-ms=999999",
        "kairo.platform.runtime-cleanup.fixed-delay-ms=999999",
        "kairo.platform.script.expiry.initial-delay-ms=999999",
        "kairo.platform.script.expiry.fixed-delay-ms=999999",
        "kairo.platform.automation.expiry.initial-delay-ms=999999",
        "kairo.platform.automation.expiry.fixed-delay-ms=999999"
})
@ActiveProfiles("test")
class ActuatorInfoTest {

    private static final Pattern HEX40 = Pattern.compile("^[0-9a-f]{40}$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectProvider<BuildProperties> buildProperties;

    @Autowired
    ObjectProvider<GitProperties> gitProperties;

    @Test
    void infoExposesExactlyBuildIdentityFields() throws Exception {
        ResponseEntity<String> resp = http.getForEntity("/actuator/info", String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        JsonNode root = MAPPER.readTree(resp.getBody());
        // No env/java/os auto contributors: "build" is the only top-level key.
        assertThat(iteratorToList(root.fieldNames())).containsExactly("build");

        JsonNode build = root.get("build");
        assertThat(build.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("version", "commit", "time", "javaTarget", "contractBaseline");

        // version: present and, when BuildProperties is generated, matches the standard build version.
        String version = build.get("version").asText();
        assertThat(version).isNotBlank();
        BuildProperties bp = buildProperties.getIfAvailable();
        if (bp != null && bp.getVersion() != null && !bp.getVersion().isBlank()) {
            assertThat(version).isEqualTo(bp.getVersion());
        }

        // git commit: present and, when GitProperties is generated, the real commit (40 hex). IDE runs fall
        // back to a documented deterministic value.
        String commit = build.get("commit").asText();
        assertThat(commit).isNotBlank();
        GitProperties gp = gitProperties.getIfAvailable();
        if (gp != null && gp.getCommitId() != null && !gp.getCommitId().isBlank()) {
            assertThat(commit).isEqualTo(gp.getCommitId());
            assertThat(commit).matches(HEX40);
        }

        // build time: present and non-blank.
        assertThat(build.get("time").asText()).isNotBlank();

        // Java target: the Platform module's actual compiler release, not merely the parent baseline.
        assertThat(build.get("javaTarget").asText()).isEqualTo("21");

        // Contract baseline: pinned to V1.6.0 / 113823b41981a2d8fb5473a772ae2d2938d9582e.
        JsonNode baseline = build.get("contractBaseline");
        assertThat(baseline.get("version").asText()).isEqualTo("V1.6.0");
        assertThat(baseline.get("commit").asText())
                .isEqualTo("113823b41981a2d8fb5473a772ae2d2938d9582e");

        // No secrets, JDBC URLs, tokens or stack traces are ever exposed.
        assertNoSecretsOrStacks(resp.getBody());
    }

    private static List<String> iteratorToList(Iterator<String> it) {
        List<String> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }

    private static void assertNoSecretsOrStacks(String body) {
        String lower = body == null ? "" : body.toLowerCase();
        assertThat(lower).doesNotContain("password", "secret", "token", "authorization",
                "jdbc:", "stacktrace", "at com.", "at org.springframework");
    }
}
