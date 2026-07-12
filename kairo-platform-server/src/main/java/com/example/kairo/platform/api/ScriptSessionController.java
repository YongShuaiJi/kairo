package com.example.kairo.platform.api;

import com.example.kairo.api.ScriptCompilationResult;
import com.example.kairo.api.ScriptSessionResult;
import com.example.kairo.platform.script.ScriptSessionEvent;
import com.example.kairo.platform.script.ScriptSessionService;
import com.example.kairo.platform.service.RbacService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Script-session lifecycle endpoints (§3.6).
 *
 * <p>{@code POST /api/v1/script-sessions} creates a trial; {@code validate}/{@code apply}/
 * {@code promote} advance it; {@code DELETE} reverts it. Each endpoint dispatches one agent command
 * and reconciles the persisted status from the ack. The page-level concerns §3.6 calls out &mdash;
 * tier, real permissions, TTL, target instance and the inability to safely hard-kill an arbitrary
 * script &mdash; are surfaced through the {@link ScriptSessionResult} fields and the diagnostics.
 */
@RestController
@RequestMapping("/api/v1/script-sessions")
@ConditionalOnProperty(prefix = "kairo.platform.api", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public final class ScriptSessionController {

    private final ScriptSessionService service;
    private final RequestContextFactory requestContextFactory;
    private final RbacService rbacService;

    public ScriptSessionController(ScriptSessionService service,
                                   RequestContextFactory requestContextFactory,
                                   RbacService rbacService) {
        this.service = service;
        this.requestContextFactory = requestContextFactory;
        this.rbacService = rbacService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScriptSessionResult create(HttpServletRequest httpRequest,
                                      @RequestBody Map<String, Object> request) {
        return service.create(context(httpRequest), request);
    }

    @GetMapping("/{id}")
    public ScriptSessionResult describe(@PathVariable String id, HttpServletRequest httpRequest) {
        return service.describe(context(httpRequest), id);
    }

    @PostMapping("/{id}/validate")
    public ScriptSessionResult validate(@PathVariable String id, HttpServletRequest httpRequest) {
        return service.validate(context(httpRequest), id);
    }

    @PostMapping("/{id}/apply")
    public ScriptSessionResult apply(@PathVariable String id, HttpServletRequest httpRequest) {
        return service.apply(context(httpRequest), id);
    }

    @PostMapping("/{id}/promote")
    public ScriptSessionResult promote(@PathVariable String id, HttpServletRequest httpRequest) {
        return service.promote(context(httpRequest), id);
    }

    @DeleteMapping("/{id}")
    public ScriptSessionResult revert(@PathVariable String id, HttpServletRequest httpRequest) {
        return service.revert(context(httpRequest), id);
    }

    @GetMapping("/{id}/events")
    public List<ScriptSessionEvent> events(@PathVariable String id, HttpServletRequest httpRequest) {
        return service.history(context(httpRequest), id);
    }

    private com.example.kairo.platform.service.RequestContext context(HttpServletRequest request) {
        return requestContextFactory.from(request);
    }
}
