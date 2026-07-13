package com.example.kairo.platform.api;

import com.example.kairo.platform.service.EnhancementTargetResolutionService;
import com.example.kairo.platform.service.PlatformJson;
import com.example.kairo.platform.service.PlatformQueryService;
import com.example.kairo.platform.service.RbacService;
import com.example.kairo.platform.service.TargetDiscoveryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "kairo.platform.api", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public final class PlatformQueryController {

    private final PlatformQueryService service;
    private final TargetDiscoveryService targetDiscoveryService;
    private final EnhancementTargetResolutionService targetResolutionService;
    private final RequestContextFactory requestContextFactory;
    private final RbacService rbacService;

    public PlatformQueryController(PlatformQueryService service,
                                   TargetDiscoveryService targetDiscoveryService,
                                   EnhancementTargetResolutionService targetResolutionService,
                                   RequestContextFactory requestContextFactory,
                                   RbacService rbacService) {
        this.service = service;
        this.targetDiscoveryService = targetDiscoveryService;
        this.targetResolutionService = targetResolutionService;
        this.requestContextFactory = requestContextFactory;
        this.rbacService = rbacService;
    }

    @GetMapping("/query/{resource}")
    public Map<String, Object> page(@PathVariable String resource,
                                    HttpServletRequest request,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "25") int size,
                                    @RequestParam(defaultValue = "") String q) {
        requireTokenAdmin(resource, request);
        return service.page(resource, page, size, q);
    }

    @GetMapping("/details/{resource}/{id}")
    public Map<String, Object> detail(@PathVariable String resource,
                                      @PathVariable String id,
                                      HttpServletRequest request) {
        requireTokenAdmin(resource, request);
        return service.detail(resource, id);
    }

    @GetMapping("/rules/{id}/detail")
    public Map<String, Object> ruleDetail(@PathVariable String id) {
        return service.ruleDetail(id);
    }

    @GetMapping("/dashboard/overview")
    public Map<String, Object> dashboard() {
        return service.dashboard();
    }

    @GetMapping("/targets/search")
    public List<Map<String, Object>> searchTargets(HttpServletRequest request,
                                                   @RequestParam(defaultValue = "") String q,
                                                   @RequestParam(defaultValue = "") String applicationId,
                                                   @RequestParam(defaultValue = "") String environmentId) {
        return targetDiscoveryService.search(requestContextFactory.from(request),
                q, applicationId, environmentId);
    }

    /**
     * V1.5 §4.1/§5: the ClassLoader tree for the Web class selector, so an operator can pick a
     * {@code classLoaderId} and disambiguate same-name classes across loaders.
     */
    @GetMapping("/targets/loaders")
    public Map<String, Object> loaderTree(HttpServletRequest request,
                                          @RequestParam(defaultValue = "") String applicationId,
                                          @RequestParam(defaultValue = "") String environmentId) {
        return targetDiscoveryService.listLoaders(requestContextFactory.from(request),
                applicationId, environmentId);
    }

    /**
     * V1.3 §3.5: enumerate call-site candidates inside a caller method on a live agent in scope,
     * for the guided call-site selector. The body carries the application/environment scope plus
     * the caller identity and an optional callee filter.
     */
    @PostMapping("/targets/call-sites")
    public Map<String, Object> callSiteCandidates(HttpServletRequest request,
                                                  @RequestBody Map<String, Object> body) {
        rbacService.require(requestContextFactory.from(request), "RULE_MANAGE");
        return targetDiscoveryService.listCallSites(requestContextFactory.from(request),
                String.valueOf(body.getOrDefault("applicationId", "")),
                String.valueOf(body.getOrDefault("environmentId", "")),
                body);
    }

    /**
     * V1.3 §3.5: resolve an enhancement target against live bytecode and return the match status,
     * matched count, risk and (for call sites) occurrence count, so the Web can preview before save.
     */
    @PostMapping("/targets/resolve")
    public Map<String, Object> resolveTarget(HttpServletRequest request,
                                             @RequestBody Map<String, Object> body) {
        rbacService.require(requestContextFactory.from(request), "RULE_MANAGE");
        Map<String, Object> target = body.get("target") instanceof Map<?, ?> map
                ? PlatformJson.stringKeyMap(map) : body;
        return targetResolutionService.resolve(requestContextFactory.from(request),
                String.valueOf(body.getOrDefault("applicationId", "")),
                String.valueOf(body.getOrDefault("environmentId", "")),
                target);
    }

    private void requireTokenAdmin(String resource, HttpServletRequest request) {
        if ("tokens".equals(resource)) {
            rbacService.require(requestContextFactory.from(request), "USER_MANAGE");
        }
    }
}
