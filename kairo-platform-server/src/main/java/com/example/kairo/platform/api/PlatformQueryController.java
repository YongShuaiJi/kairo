package com.example.kairo.platform.api;

import com.example.kairo.platform.service.PlatformQueryService;
import com.example.kairo.platform.service.RbacService;
import com.example.kairo.platform.service.TargetDiscoveryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final RequestContextFactory requestContextFactory;
    private final RbacService rbacService;

    public PlatformQueryController(PlatformQueryService service,
                                   TargetDiscoveryService targetDiscoveryService,
                                   RequestContextFactory requestContextFactory,
                                   RbacService rbacService) {
        this.service = service;
        this.targetDiscoveryService = targetDiscoveryService;
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

    private void requireTokenAdmin(String resource, HttpServletRequest request) {
        if ("tokens".equals(resource)) {
            rbacService.require(requestContextFactory.from(request), "USER_MANAGE");
        }
    }
}
