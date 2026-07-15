package com.example.kairo.platform.api;

import com.example.kairo.api.ReconcileResult;
import com.example.kairo.platform.service.PlatformException;
import com.example.kairo.platform.service.RequestContext;
import com.example.kairo.platform.service.RuleChainStateService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * V1.6 &sect;3 rule-chain read + reconciliation resources. Surfaces the desired vs
 * actual chain state and a structured reconcile result so callers (and AI) can
 * detect drift without parsing audit prose.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "kairo.platform.api", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public final class RuleChainController {

    private final RuleChainStateService ruleChainStateService;
    private final RequestContextFactory requestContextFactory;

    public RuleChainController(RuleChainStateService ruleChainStateService,
                               RequestContextFactory requestContextFactory) {
        this.ruleChainStateService = ruleChainStateService;
        this.requestContextFactory = requestContextFactory;
    }

    @GetMapping("/rule-chains")
    public Map<String, Object> describe(@RequestParam String applicationId,
                                        @RequestParam String environmentId,
                                        @RequestParam String agentId,
                                        @RequestParam String chainId,
                                        HttpServletRequest request) {
        context(request);
        return ruleChainStateService.describe(applicationId, environmentId, agentId, chainId);
    }

    @PostMapping("/reconciliations")
    public ReconcileResult reconcile(@RequestBody Map<String, Object> body,
                                     HttpServletRequest request) {
        RequestContext ctx = context(request);
        String applicationId = required(body, "applicationId");
        String environmentId = required(body, "environmentId");
        String agentId = required(body, "agentId");
        String chainId = required(body, "chainId");
        return ruleChainStateService.reconcileCurrent(applicationId, environmentId, agentId, chainId);
    }

    private RequestContext context(HttpServletRequest request) {
        return requestContextFactory.from(request);
    }

    private static String required(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw PlatformException.badRequest("MISSING_FIELD", key + " is required");
        }
        return String.valueOf(value);
    }
}
