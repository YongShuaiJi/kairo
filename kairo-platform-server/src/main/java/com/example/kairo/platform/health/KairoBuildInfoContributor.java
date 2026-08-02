package com.example.kairo.platform.health;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V1.7 M4-A &sect;11.1: the single, bounded, secret-free build-identity object exposed at
 * {@code /actuator/info}. Spring's auto build/git/env/java/os info contributors are disabled
 * (see {@code management.info.*.enabled=false} in {@code application.yml}); this contributor owns the
 * output so exactly the useful build-identity fields are exposed: version, git commit, build time, the
 * Platform artifact Java target, and the pinned V1.6.0 contract baseline.
 *
 * <p>Identity derivation lives in {@link KairoBuildIdentity} so the M4-B {@code kairo_platform_build_info}
 * gauge reuses the exact same version/commit. It never exposes secrets, tokens, JDBC URLs or stack traces.
 *
 * <p>M4-A intentionally does not perform the M5 global version unification: the version reflects the
 * current Maven {@code project.version} and the contract baseline stays pinned to V1.6.0 /
 * {@code 113823b41981a2d8fb5473a772ae2d2938d9582e}.
 */
@Component
public class KairoBuildInfoContributor implements InfoContributor {

    private final ObjectProvider<BuildProperties> buildPropertiesProvider;
    private final ObjectProvider<GitProperties> gitPropertiesProvider;

    public KairoBuildInfoContributor(ObjectProvider<BuildProperties> buildPropertiesProvider,
                                     ObjectProvider<GitProperties> gitPropertiesProvider) {
        this.buildPropertiesProvider = buildPropertiesProvider;
        this.gitPropertiesProvider = gitPropertiesProvider;
    }

    @Override
    public void contribute(Info.Builder builder) {
        BuildProperties build = buildPropertiesProvider.getIfAvailable();
        GitProperties git = gitPropertiesProvider.getIfAvailable();

        Map<String, Object> buildInfo = new LinkedHashMap<>();
        buildInfo.put("version", KairoBuildIdentity.version(build));
        buildInfo.put("commit", KairoBuildIdentity.commit(git));
        buildInfo.put("time", KairoBuildIdentity.time(build));
        buildInfo.put("javaTarget", KairoBuildIdentity.javaTarget(build));
        buildInfo.put("contractBaseline", Map.of(
                "version", KairoBuildIdentity.CONTRACT_BASELINE_VERSION,
                "commit", KairoBuildIdentity.CONTRACT_BASELINE_COMMIT));

        builder.withDetail("build", buildInfo);
    }
}
