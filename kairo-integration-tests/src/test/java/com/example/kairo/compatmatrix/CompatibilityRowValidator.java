package com.example.kairo.compatmatrix;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure schema/content validator for one compatibility row JSON document
 * (the output of {@code run-compatibility.sh --scenario <id> --output <row.json>}).
 *
 * <p>Mirrors the {@code SoakResultValidator}/{@code LeakResultValidator} pattern: a
 * pure function returning a list of error strings (empty = valid). The row runner
 * self-validates with this after writing the file; non-empty errors -> exit 6.
 *
 * <p>A row is valid only when: the schema/catalog versions are fixed; the scenario is
 * a known C01-C10 and its embedded {@code catalog} block matches the frozen catalog
 * exactly (OS, arch, JDK(s), load mode, fixture, required behaviors); the build id
 * is a 40-hex commit; the command is non-placeholder; the environment, target JVM,
 * timestamps and assertions are well-typed; and the status is one of
 * {@code PASSED}/{@code FAILED}/{@code SKIPPED}/{@code NOT_RUN}/{@code EXPERIMENTAL}.
 *
 * <p>PASSED is fail-closed: a PASSED row must carry a real independent child PID
 * ({@code targetJvm.pid > 0}, {@code independent=true}), a target JDK that is one
 * of the catalog's declared JDKs, a runner OS/arch that matches the catalog, and a
 * non-empty assertion set where every assertion passed and every required behavior
 * is covered. Any PASSED row missing those is rejected as fake evidence. This is
 * what prevents a fabricated PASSED from slipping through (section 10.3 / 10.4.1).
 */
public final class CompatibilityRowValidator {

    private static final Pattern HEX40 = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern PLACEHOLDER = Pattern.compile("<[^>]*>|\\.\\.\\.");

    public static final Set<String> STATUSES = Set.of(
            "PASSED", "FAILED", "SKIPPED", "NOT_RUN", "EXPERIMENTAL");

    public List<String> validate(JsonNode root) {
        List<String> errors = new ArrayList<>();
        if (root == null || root.isMissingNode() || root.isNull()) {
            errors.add("row is null/missing");
            return errors;
        }
        requireText(errors, root, "schemaVersion", CompatibilityScenarioCatalog.SCHEMA_VERSION);
        requireText(errors, root, "catalogVersion", CompatibilityScenarioCatalog.CATALOG_VERSION);

        String scenarioId = textOrNull(root, "scenario");
        if (scenarioId == null || scenarioId.isBlank()) {
            errors.add("scenario is required (C01-C10)");
            return errors;
        }
        CompatibilityScenario scenario = CompatibilityScenarioCatalog.scenario(scenarioId);
        if (scenario == null) {
            errors.add("unknown scenario: " + scenarioId + " (must be C01-C10)");
            return errors;
        }

        String supportLevel = textOrNull(root, "supportLevel");
        if (!scenario.supportLevel().name().equals(supportLevel)) {
            errors.add("supportLevel must be '" + scenario.supportLevel()
                    + "' for " + scenarioId + " (got: " + supportLevel + ")");
        }

        // The embedded catalog block must match the frozen catalog exactly.
        validateCatalogBlock(errors, root.path("catalog"), scenario);

        String buildId = textOrNull(root, "buildId");
        if (buildId == null) {
            errors.add("missing buildId");
        } else if (!HEX40.matcher(buildId).matches()) {
            errors.add("buildId must be a 40-hex lowercase commit id (got: " + buildId + ")");
        }

        String command = textOrNull(root, "command");
        if (command == null || command.isBlank()) {
            errors.add("missing command");
        } else if (PLACEHOLDER.matcher(command).find()) {
            errors.add("command must not contain <...> or ... placeholders");
        }

        // Provenance: evidence mode and working-tree state. PR evidence must not come
        // from a dirty tree; --allow-dirty records mode=dev.
        String mode = textOrNull(root, "mode");
        if (!"pr".equals(mode) && !"dev".equals(mode)) {
            errors.add("mode must be 'pr' or 'dev' (got: " + mode + ")");
        }
        JsonNode dirty = root.path("workingTreeDirty");
        if (!dirty.isBoolean()) {
            errors.add("workingTreeDirty must be boolean");
        } else if ("pr".equals(mode) && dirty.asBoolean()) {
            errors.add("PR evidence (mode=pr) must not have a dirty working tree");
        }

        validateEnvironment(errors, root.path("environment"), scenario);
        validateTargetJvm(errors, root.path("targetJvm"));

        String loadingMode = textOrNull(root, "loadingMode");
        if (!scenario.loadModeRaw().equals(loadingMode)) {
            errors.add("loadingMode must equal the catalog load mode '" + scenario.loadModeRaw()
                    + "' (got: " + loadingMode + ")");
        }
        String fixture = textOrNull(root, "fixture");
        if (!scenario.fixture().equals(fixture)) {
            errors.add("fixture must equal the catalog fixture '" + scenario.fixture()
                    + "' (got: " + fixture + ")");
        }

        String startedAt = textOrNull(root, "startedAt");
        String endedAt = textOrNull(root, "endedAt");
        requireNonBlankText(errors, root, "startedAt");
        requireNonBlankText(errors, root, "endedAt");
        if (startedAt != null && endedAt != null) {
            Instant start = tryParseInstant(startedAt, errors, "startedAt");
            Instant end = tryParseInstant(endedAt, errors, "endedAt");
            if (start != null && end != null && end.isBefore(start)) {
                errors.add("endedAt must not be before startedAt");
            }
        }

        validateAssertions(errors, root.path("assertions"), scenario);

        String status = textOrNull(root, "status");
        if (status == null || !STATUSES.contains(status)) {
            errors.add("status must be one of " + STATUSES + " (got: " + status + ")");
            return errors;
        }
        JsonNode reason = root.path("failureReason");
        boolean hasReason = reason.isTextual() && !reason.asText().isBlank();
        if ("PASSED".equals(status)) {
            if (hasReason) {
                errors.add("status is PASSED but failureReason is present");
            }
        } else {
            if (!hasReason) {
                errors.add("status is " + status + " but failureReason is missing/blank");
            }
        }

        // Fail-closed PASSED/FAILED: a fabricated outcome cannot pass. A PASSED row must
        // carry real independent child evidence; a FAILED row must have actually run.
        if ("PASSED".equals(status)) {
            validatePassedEvidence(errors, root, scenario);
        } else if ("FAILED".equals(status)) {
            validateFailedEvidence(errors, root, scenario);
        }
        return errors;
    }

    private void validateCatalogBlock(List<String> errors, JsonNode cat, CompatibilityScenario scenario) {
        if (!cat.isObject()) {
            errors.add("missing catalog block");
            return;
        }
        requireText(errors, cat, "runnerOs", scenario.runnerOs());
        requireText(errors, cat, "runnerArch", scenario.runnerArch());
        JsonNode jdks = cat.path("targetJdks");
        if (!jdks.isArray() || jdks.size() != scenario.targetJdks().size()) {
            errors.add("catalog.targetJdks must equal " + scenario.targetJdks()
                    + " for " + scenario.id());
        } else {
            for (int i = 0; i < jdks.size(); i++) {
                if (!jdks.get(i).isInt() || jdks.get(i).asInt() != scenario.targetJdks().get(i)) {
                    errors.add("catalog.targetJdks must equal " + scenario.targetJdks()
                            + " for " + scenario.id());
                    break;
                }
            }
        }
        requireText(errors, cat, "loadMode", scenario.loadMode().name());
        requireText(errors, cat, "loadModeRaw", scenario.loadModeRaw());
        requireText(errors, cat, "fixture", scenario.fixture());
        requireText(errors, cat, "requiredBehaviorsRaw", scenario.requiredBehaviorsRaw());
        JsonNode rb = cat.path("requiredBehaviors");
        if (!rb.isArray() || rb.size() != scenario.requiredBehaviors().size()) {
            errors.add("catalog.requiredBehaviors must equal " + scenario.requiredBehaviors()
                    + " for " + scenario.id());
        } else {
            for (int i = 0; i < rb.size(); i++) {
                if (!rb.get(i).isTextual()
                        || !rb.get(i).asText().equals(scenario.requiredBehaviors().get(i))) {
                    errors.add("catalog.requiredBehaviors must equal " + scenario.requiredBehaviors()
                            + " for " + scenario.id());
                    break;
                }
            }
        }
    }

    private void validateEnvironment(List<String> errors, JsonNode env, CompatibilityScenario scenario) {
        if (!env.isObject()) {
            errors.add("missing environment object");
            return;
        }
        requireNonBlankText(errors, env, "osName");
        requireNonBlankText(errors, env, "osArch");
        requireNonBlankText(errors, env, "jdkVersion");
        // The runner's own PID - independent-process provenance. Always > 0.
        JsonNode runnerPid = env.path("runnerPid");
        if (!runnerPid.isIntegralNumber() || runnerPid.asLong() <= 0) {
            errors.add("environment.runnerPid must be a positive integer (the runner's own PID)");
        }
    }

    private void validateTargetJvm(List<String> errors, JsonNode t) {
        if (!t.isObject()) {
            errors.add("missing targetJvm object");
            return;
        }
        JsonNode pid = t.path("pid");
        if (!pid.isInt() || pid.asInt() < 0) {
            errors.add("targetJvm.pid must be a non-negative integer (real child PID, 0 if none)");
        }
        if (!t.path("independent").isBoolean()) {
            errors.add("targetJvm.independent must be boolean");
        }
        // jdkVersion must be present (textual) but may be blank when no child ran
        // (NOT_RUN / SKIPPED / EXPERIMENTAL without a runner). PASSED/FAILED require it.
        JsonNode jdk = t.path("jdkVersion");
        if (!jdk.isTextual()) {
            errors.add("targetJvm.jdkVersion must be a string (blank if no child ran)");
        }
    }

    private void validateAssertions(List<String> errors, JsonNode assertions, CompatibilityScenario scenario) {
        if (!assertions.isArray()) {
            errors.add("assertions must be an array");
            return;
        }
        for (int i = 0; i < assertions.size(); i++) {
            JsonNode a = assertions.get(i);
            if (!a.isObject()) {
                errors.add("assertion[" + i + "] must be an object");
                continue;
            }
            JsonNode name = a.path("name");
            if (!name.isTextual() || name.asText().isBlank()) {
                errors.add("assertion[" + i + "].name must be a non-blank string");
            }
            if (!a.path("passed").isBoolean()) {
                errors.add("assertion[" + i + "].passed must be boolean");
            }
        }
    }

    private void validatePassedEvidence(List<String> errors, JsonNode root, CompatibilityScenario scenario) {
        JsonNode t = root.path("targetJvm");
        JsonNode pid = t.path("pid");
        if (!pid.isInt() || pid.asInt() <= 0) {
            errors.add("PASSED requires a real independent child PID > 0 (got: "
                    + (pid.isInt() ? pid.asInt() : "missing") + ")");
        }
        JsonNode independent = t.path("independent");
        if (!independent.isBoolean() || !independent.asBoolean()) {
            errors.add("PASSED requires targetJvm.independent=true (independent target process)");
        }
        requireDistinctChildPid(errors, root, t, "PASSED");
        JsonNode tjdk = t.path("jdkVersion");
        if (tjdk.isTextual()) {
            int major = PlatformNormals.majorJdk(tjdk.asText());
            if (major <= 0) {
                errors.add("PASSED requires a parseable target JVM jdkVersion (got: " + tjdk.asText() + ")");
            } else if (!scenario.targetJdks().contains(major)) {
                errors.add("PASSED target JVM jdkVersion " + major
                        + " is not in the catalog target JDKs " + scenario.targetJdks()
                        + " for " + scenario.id());
            }
        }
        // Runner OS/arch must match the catalog (no passing a Linux scenario on macOS).
        JsonNode env = root.path("environment");
        String osName = textOrNull(env, "osName");
        String osArch = textOrNull(env, "osArch");
        if (osName != null && !PlatformNormals.normalizeOs(osName).equals(scenario.runnerOs())) {
            errors.add("PASSED environment.osName must match the catalog runner OS '"
                    + scenario.runnerOs() + "' (got: " + osName + ")");
        }
        if (osArch != null && !PlatformNormals.normalizeArch(osArch).equals(scenario.runnerArch())) {
            errors.add("PASSED environment.osArch must match the catalog runner arch '"
                    + scenario.runnerArch() + "' (got: " + osArch + ")");
        }
        // Every assertion must have passed, and every required behavior must be covered.
        JsonNode assertions = root.path("assertions");
        if (!assertions.isArray() || assertions.isEmpty()) {
            errors.add("PASSED requires a non-empty assertions array");
            return;
        }
        for (int i = 0; i < assertions.size(); i++) {
            JsonNode p = assertions.get(i).path("passed");
            if (p.isBoolean() && !p.asBoolean()) {
                errors.add("PASSED requires every assertion.passed=true (assertion[" + i + "] is false)");
            }
        }
        java.util.Set<String> covered = new HashSet<>();
        for (JsonNode a : assertions) {
            JsonNode n = a.path("name");
            if (n.isTextual()) {
                covered.add(n.asText());
            }
        }
        for (String b : scenario.requiredBehaviors()) {
            if (!covered.contains(b)) {
                errors.add("PASSED requires an assertion covering required behavior '" + b
                        + "' for " + scenario.id());
            }
        }
    }

    private void validateFailedEvidence(List<String> errors, JsonNode root, CompatibilityScenario scenario) {
        JsonNode t = root.path("targetJvm");
        JsonNode pid = t.path("pid");
        if (!pid.isInt() || pid.asInt() <= 0) {
            errors.add("FAILED requires a real independent child PID > 0 (it ran, got: "
                    + (pid.isInt() ? pid.asInt() : "missing") + ")");
        }
        JsonNode independent = t.path("independent");
        if (!independent.isBoolean() || !independent.asBoolean()) {
            errors.add("FAILED requires targetJvm.independent=true (independent target process)");
        }
        requireDistinctChildPid(errors, root, t, "FAILED");
        JsonNode tjdk = t.path("jdkVersion");
        if (!tjdk.isTextual() || tjdk.asText().isBlank()) {
            errors.add("FAILED requires a non-blank targetJvm.jdkVersion (it ran)");
        }
        // A FAILED run must have run at least one assertion, and at least one must have failed.
        JsonNode assertions = root.path("assertions");
        if (!assertions.isArray() || assertions.isEmpty()) {
            errors.add("FAILED requires a non-empty assertions array (it ran)");
            return;
        }
        boolean anyFailed = false;
        for (JsonNode a : assertions) {
            JsonNode p = a.path("passed");
            if (p.isBoolean() && !p.asBoolean()) {
                anyFailed = true;
            }
        }
        if (!anyFailed) {
            errors.add("FAILED requires at least one assertion with passed=false");
        }
    }

    // -------------------------------------------------------- helpers

    /** For PASSED/FAILED: the target JVM PID must differ from the runner's own PID. */
    private static void requireDistinctChildPid(List<String> errors, JsonNode root, JsonNode targetJvm, String status) {
        JsonNode pid = targetJvm.path("pid");
        JsonNode runnerPid = root.path("environment").path("runnerPid");
        if (pid.isIntegralNumber() && pid.asLong() > 0
                && runnerPid.isIntegralNumber() && runnerPid.asLong() > 0
                && pid.asLong() == runnerPid.asLong()) {
            errors.add(status + " requires targetJvm.pid != environment.runnerPid "
                    + "(the target must be an independent process, got pid=" + pid.asLong()
                    + " == runnerPid=" + runnerPid.asLong() + ")");
        }
    }

    private static String textOrNull(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        return n.isTextual() ? n.asText() : null;
    }

    private static void requireText(List<String> errors, JsonNode parent, String field, String expected) {
        JsonNode n = parent.path(field);
        if (!n.isTextual() || !expected.equals(n.asText())) {
            errors.add(field + " must equal '" + expected + "' (got: "
                    + (n.isTextual() ? n.asText() : "missing") + ")");
        }
    }

    private static void requireNonBlankText(List<String> errors, JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        if (!n.isTextual() || n.asText().isBlank()) {
            errors.add(field + " must be a non-blank string");
        }
    }

    private static Instant tryParseInstant(String value, List<String> errors, String field) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            errors.add(field + " must be an ISO-8601 instant (got: " + value + ")");
            return null;
        }
    }
}
