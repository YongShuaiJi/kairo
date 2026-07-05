package com.example.kairo.platform.api;

import com.example.kairo.platform.service.RbacService;
import com.example.kairo.platform.service.ScriptWorkbenchService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/scripts")
@ConditionalOnProperty(prefix = "kairo.platform.api", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public final class ScriptWorkbenchController {

    private final ScriptWorkbenchService service;
    private final RequestContextFactory requestContextFactory;
    private final RbacService rbacService;

    public ScriptWorkbenchController(ScriptWorkbenchService service,
                                     RequestContextFactory requestContextFactory,
                                     RbacService rbacService) {
        this.service = service;
        this.requestContextFactory = requestContextFactory;
        this.rbacService = rbacService;
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(HttpServletRequest httpRequest,
                                        @RequestBody Map<String, Object> request) {
        rbacService.require(requestContextFactory.from(httpRequest), "RULE_MANAGE");
        return service.validate(request);
    }

    @PostMapping({"/test", "/preview"})
    public Map<String, Object> test(HttpServletRequest httpRequest,
                                    @RequestBody Map<String, Object> request) {
        rbacService.require(requestContextFactory.from(httpRequest), "RULE_MANAGE");
        return service.test(request);
    }
}
