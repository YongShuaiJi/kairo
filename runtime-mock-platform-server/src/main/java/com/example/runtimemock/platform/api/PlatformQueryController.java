package com.example.runtimemock.platform.api;

import com.example.runtimemock.platform.service.PlatformQueryService;
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
@ConditionalOnProperty(prefix = "runtime-mock.platform.api", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public final class PlatformQueryController {

    private final PlatformQueryService service;

    public PlatformQueryController(PlatformQueryService service) {
        this.service = service;
    }

    @GetMapping("/query/{resource}")
    public Map<String, Object> page(@PathVariable String resource,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "25") int size,
                                    @RequestParam(defaultValue = "") String q) {
        return service.page(resource, page, size, q);
    }

    @GetMapping("/details/{resource}/{id}")
    public Map<String, Object> detail(@PathVariable String resource, @PathVariable String id) {
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
    public List<Map<String, Object>> searchTargets(@RequestParam(defaultValue = "") String q,
                                                   @RequestParam(defaultValue = "") String applicationId,
                                                   @RequestParam(defaultValue = "") String environmentId) {
        return service.searchTargets(q, applicationId, environmentId);
    }
}
