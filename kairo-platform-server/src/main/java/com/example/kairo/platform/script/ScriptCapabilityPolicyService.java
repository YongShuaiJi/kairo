package com.example.kairo.platform.script;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.ScriptPolicyRevision;
import com.example.kairo.platform.persistence.mapper.ScriptCapabilityPolicyMapper;
import com.example.kairo.platform.service.BusinessIdService;
import com.example.kairo.platform.service.PlatformCoreService;
import com.example.kairo.platform.service.PlatformException;
import com.example.kairo.platform.service.PlatformJson;
import com.example.kairo.platform.service.RbacService;
import com.example.kairo.platform.service.RequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Computes the effective capability tier and manages per-application capability ceilings.
 *
 * <p>§2.1: the effective tier is {@code min(platform allowed max, application allowed max, rule
 * requested tier)}. The platform ceiling lives in {@code script_capability_policy} under the
 * PLATFORM scope (seeded by the V36 migration); an operator may override it with the
 * {@code kairo.platform.script.platform-max-profile} property. The application ceiling is managed
 * through {@code PUT /api/v1/apps/{appId}/script-policy} with optimistic locking on {@code revision};
 * every update bumps the revision and recomputes the policy hash so the agent compile cache always
 * sees a new key. Sessions pin the (revision, hash) pair at creation time.
 */
@Service
public class ScriptCapabilityPolicyService {

    /** Default platform ceiling when neither config nor a DB row supplies one. */
    public static final CapabilityProfile DEFAULT_PLATFORM_MAX = CapabilityProfile.UNRESTRICTED;
    /** Default application ceiling when no APPLICATION policy row exists yet. */
    public static final CapabilityProfile DEFAULT_APPLICATION_MAX = CapabilityProfile.UNRESTRICTED;

    private final ScriptCapabilityPolicyMapper mapper;
    private final RbacService rbacService;
    private final PlatformCoreService events;
    private final BusinessIdService businessIdService;
    private final Clock clock;

    @Value("${kairo.platform.script.platform-max-profile:}")
    private String configuredPlatformMax;

    @Autowired
    public ScriptCapabilityPolicyService(ScriptCapabilityPolicyMapper mapper,
                                         RbacService rbacService,
                                         PlatformCoreService events,
                                         BusinessIdService businessIdService) {
        this(mapper, rbacService, events, businessIdService, Clock.systemUTC());
    }

    ScriptCapabilityPolicyService(ScriptCapabilityPolicyMapper mapper,
                                  RbacService rbacService,
                                  PlatformCoreService events,
                                  BusinessIdService businessIdService,
                                  Clock clock) {
        this.mapper = mapper;
        this.rbacService = rbacService;
        this.events = events;
        this.businessIdService = businessIdService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reads

    /** The platform-wide ceiling; config override wins over the seeded DB row. */
    public CapabilityProfile platformMax() {
        if (configuredPlatformMax != null && !configuredPlatformMax.isBlank()) {
            return parseProfile(configuredPlatformMax, "kairo.platform.script.platform-max-profile");
        }
        ScriptCapabilityPolicy row = mapper.findPlatform();
        return row == null ? DEFAULT_PLATFORM_MAX : CapabilityProfile.valueOf(row.allowedMaxProfile());
    }

    /** The application ceiling, defaulting to UNRESTRICTED when no policy has been set. */
    public CapabilityProfile applicationMax(String applicationId) {
        requireText(applicationId, "applicationId");
        ScriptCapabilityPolicy row = mapper.findApplication(applicationId);
        return row == null ? DEFAULT_APPLICATION_MAX : CapabilityProfile.valueOf(row.allowedMaxProfile());
    }

    /** {@code min(platform, application, requested)} per §2.1. */
    public CapabilityProfile effective(String applicationId, CapabilityProfile requested) {
        Objects.requireNonNull(requested, "requested");
        return CapabilityProfile.effective(platformMax(), applicationMax(applicationId), requested);
    }

    /**
     * The (revision, hash) to pin on a session created now. The revision is the application policy's
     * revision (0 when no application policy exists); the hash covers the full effective ceiling so a
     * platform-ceiling change (which does not bump the application revision) still yields a new cache
     * key on the agent.
     */
    public ScriptPolicyRevision revisionToPin(String applicationId) {
        requireText(applicationId, "applicationId");
        ScriptCapabilityPolicy row = mapper.findApplication(applicationId);
        long revision = row == null ? 0L : row.revision();
        String hash = effectivePolicyHash(platformMax(), row == null
                ? DEFAULT_APPLICATION_MAX : CapabilityProfile.valueOf(row.allowedMaxProfile()), revision);
        return new ScriptPolicyRevision(revision, hash);
    }

    /** Snapshot of the application policy and the computed ceilings, for the GET endpoint. */
    public Map<String, Object> describe(String applicationId) {
        requireText(applicationId, "applicationId");
        ScriptCapabilityPolicy row = mapper.findApplication(applicationId);
        CapabilityProfile platformMax = platformMax();
        CapabilityProfile appMax = row == null ? DEFAULT_APPLICATION_MAX
                : CapabilityProfile.valueOf(row.allowedMaxProfile());
        CapabilityProfile effective = CapabilityProfile.effective(platformMax, appMax, DEFAULT_APPLICATION_MAX);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationId", applicationId);
        result.put("platformMaxProfile", platformMax.name());
        result.put("applicationMaxProfile", appMax.name());
        result.put("effectiveMaxProfile", effective.name());
        result.put("hasApplicationPolicy", row != null);
        if (row != null) {
            result.put("revision", row.revision());
            result.put("policyHash", row.policyHash());
            result.put("modifiedBy", row.modifiedBy());
            result.put("updatedAt", row.updatedAt() == null ? null : row.updatedAt().toInstant().toString());
        } else {
            result.put("revision", 0L);
            result.put("policyHash", effectivePolicyHash(platformMax, appMax, 0L));
        }
        return result;
    }

    // ------------------------------------------------------------------ writes

    /** Upsert the application ceiling with optimistic locking on revision; bumps revision and hash. */
    @Transactional
    public Map<String, Object> put(RequestContext context, String applicationId, Map<String, Object> request) {
        rbacService.require(context, "RULE_MANAGE");
        requireText(applicationId, "applicationId");
        CapabilityProfile requested = parseProfile(requiredString(request, "allowedMaxProfile"),
                "allowedMaxProfile");
        Long expectedRevision = optionalLong(request, "expectedRevision");
        Instant now = clock.instant();
        Timestamp timestamp = Timestamp.from(now);

        ScriptCapabilityPolicy current = mapper.findApplication(applicationId);
        if (current == null) {
            long revision = 1L;
            ScriptCapabilityPolicy created = new ScriptCapabilityPolicy(
                    ScriptCapabilityPolicy.APPLICATION, applicationId, requested.name(), revision,
                    rowHash(applicationId, requested, revision), context.actor(),
                    timestamp, timestamp);
            try {
                mapper.insert(created);
            } catch (DuplicateKeyException concurrentInsert) {
                return put(context, applicationId, request);
            }
            recordAudit(context, applicationId, null, created, "script_policy.create",
                    "Created application script capability policy");
            return describe(applicationId);
        }
        if (expectedRevision == null || expectedRevision != current.revision()) {
            throw PlatformException.conflict("RESOURCE_VERSION_CONFLICT",
                    "Script policy revision has changed; refresh and retry",
                    Map.of("applicationId", applicationId,
                            "currentRevision", current.revision(),
                            "expectedRevision", String.valueOf(expectedRevision)));
        }
        if (current.allowedMaxProfile().equals(requested.name())) {
            return describe(applicationId);
        }
        long newRevision = current.revision() + 1L;
        ScriptCapabilityPolicy updated = new ScriptCapabilityPolicy(
                ScriptCapabilityPolicy.APPLICATION, applicationId, requested.name(), newRevision,
                rowHash(applicationId, requested, newRevision), context.actor(),
                current.createdAt(), timestamp);
        int changed = mapper.update(updated, current.revision());
        if (changed == 0) {
            throw PlatformException.conflict("RESOURCE_VERSION_CONFLICT",
                    "Script policy revision has changed; refresh and retry",
                    Map.of("applicationId", applicationId, "currentRevision", current.revision()));
        }
        recordAudit(context, applicationId, current, updated, "script_policy.update",
                "Updated application script capability policy");
        return describe(applicationId);
    }

    private void recordAudit(RequestContext context, String applicationId,
                             ScriptCapabilityPolicy before, ScriptCapabilityPolicy after,
                             String action, String reason) {
        events.recordEvent(context, action, "script_capability_policy", applicationId,
                after.revision(), before, after, "SUCCESS", reason,
                Map.of("allowedMaxProfile", after.allowedMaxProfile(),
                        "revision", after.revision()));
    }

    private String rowHash(String applicationId, CapabilityProfile profile, long revision) {
        return PlatformJson.sha256(Map.of(
                "scope", ScriptCapabilityPolicy.APPLICATION,
                "applicationId", applicationId,
                "allowedMaxProfile", profile.name(),
                "revision", revision));
    }

    private String effectivePolicyHash(CapabilityProfile platformMax, CapabilityProfile appMax, long revision) {
        return PlatformJson.sha256(Map.of(
                "platformMaxProfile", platformMax.name(),
                "applicationMaxProfile", appMax.name(),
                "revision", revision));
    }

    private static CapabilityProfile parseProfile(String value, String field) {
        try {
            return CapabilityProfile.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw PlatformException.badRequest("INVALID_FIELD",
                    field + " must be SAFE, EXTENDED or UNRESTRICTED");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw PlatformException.badRequest("FIELD_REQUIRED", "Missing required field: " + name);
        }
        return value;
    }

    private static String requiredString(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw PlatformException.badRequest("FIELD_REQUIRED", "Missing required field: " + key);
        }
        return String.valueOf(value);
    }

    private static Long optionalLong(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw PlatformException.badRequest("INVALID_FIELD", key + " must be a non-negative integer");
        }
    }
}
