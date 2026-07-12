package com.example.kairo.platform.api;

import com.example.kairo.platform.script.ScriptCapabilityPolicyService;
import com.example.kairo.platform.service.RbacService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code GET/PUT /api/v1/apps/{appId}/script-policy} (§3.6).
 *
 * <p>Exposes the per-application capability ceiling and the computed effective tier. The PUT uses
 * optimistic locking on {@code revision} (supplied as {@code expectedRevision} in the body); every
 * update bumps the revision and recomputes the policy hash so agent compile caches invalidate.
 */
@RestController
@RequestMapping("/api/v1/apps/{appId}/script-policy")
@ConditionalOnProperty(prefix = "kairo.platform.api", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public final class ScriptCapabilityPolicyController {

    private final ScriptCapabilityPolicyService service;
    private final RequestContextFactory requestContextFactory;
    private final RbacService rbacService;

    public ScriptCapabilityPolicyController(ScriptCapabilityPolicyService service,
                                            RequestContextFactory requestContextFactory,
                                            RbacService rbacService) {
        this.service = service;
        this.requestContextFactory = requestContextFactory;
        this.rbacService = rbacService;
    }

    @GetMapping
    public Map<String, Object> get(@PathVariable String appId, HttpServletRequest httpRequest) {
        rbacService.require(requestContextFactory.from(httpRequest), "RULE_MANAGE");
        return service.describe(appId);
    }

    @PutMapping
    public Map<String, Object> put(@PathVariable String appId,
                                   HttpServletRequest httpRequest,
                                   @RequestBody Map<String, Object> request) {
        return service.put(requestContextFactory.from(httpRequest), appId, request);
    }
}
