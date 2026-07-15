package com.example.kairo.platform.api;

import com.example.kairo.api.automation.AutomationSession;
import com.example.kairo.api.automation.EnhancementContextBundle;
import com.example.kairo.api.write.PreviewResult;
import com.example.kairo.platform.automation.AutomationSessionService;
import com.example.kairo.platform.service.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI task-level API (V1.6 &sect;4.2). Every step references a stable
 * {@code AutomationSession} id and, for the final apply, a stable target id and
 * preview revision &mdash; never an opaque "find and enhance" intent.
 */
@RestController
@RequestMapping("/api/v1/automation-sessions")
@ConditionalOnProperty(prefix = "kairo.platform.api", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public final class AutomationSessionController {

    private final AutomationSessionService sessionService;
    private final RequestContextFactory requestContextFactory;

    public AutomationSessionController(AutomationSessionService sessionService,
                                       RequestContextFactory requestContextFactory) {
        this.sessionService = sessionService;
        this.requestContextFactory = requestContextFactory;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AutomationSession create(@RequestBody AutomationSessionService.CreateRequest request,
                                    HttpServletRequest httpRequest) {
        return sessionService.create(context(httpRequest), request);
    }

    @GetMapping("/{id}")
    public AutomationSession get(@PathVariable String id, HttpServletRequest httpRequest) {
        return sessionService.get(context(httpRequest), id);
    }

    @GetMapping
    public List<AutomationSession> list(@RequestParam(required = false) String status,
                                        HttpServletRequest httpRequest) {
        return sessionService.list(context(httpRequest), status);
    }

    @PostMapping("/{id}/resolve-targets")
    public EnhancementContextBundle resolveTargets(@PathVariable String id,
                                                   @RequestBody AutomationSessionService.ResolveTargetsRequest request,
                                                   HttpServletRequest httpRequest) {
        return sessionService.resolveTargets(context(httpRequest), id, request);
    }

    @PostMapping("/{id}/validate-script")
    public Map<String, Object> validateScript(@PathVariable String id,
                                              @RequestBody AutomationSessionService.ValidateScriptRequest request,
                                              HttpServletRequest httpRequest) {
        return sessionService.validateScript(context(httpRequest), id, request);
    }

    @PostMapping("/{id}/preview")
    public PreviewResult preview(@PathVariable String id,
                                 @RequestBody AutomationSessionService.PreviewRequest request,
                                 HttpServletRequest httpRequest) {
        return sessionService.preview(context(httpRequest), id, request);
    }

    @PostMapping("/{id}/trial")
    public Map<String, Object> trial(@PathVariable String id,
                                     @RequestBody AutomationSessionService.TrialRequest request,
                                     HttpServletRequest httpRequest) {
        return sessionService.trial(context(httpRequest), id, request);
    }

    @PostMapping("/{id}/promote")
    public Map<String, Object> promote(@PathVariable String id,
                                       @RequestBody Map<String, Object> request,
                                       HttpServletRequest httpRequest) {
        String scriptSessionId = String.valueOf(request.get("scriptSessionId"));
        return sessionService.promote(context(httpRequest), id, scriptSessionId);
    }

    @PostMapping("/{id}/revert")
    public AutomationSession revert(@PathVariable String id, HttpServletRequest httpRequest) {
        return sessionService.revert(context(httpRequest), id);
    }

    @GetMapping("/{id}/events")
    public List<Map<String, Object>> events(@PathVariable String id, HttpServletRequest httpRequest) {
        return sessionService.events(context(httpRequest), id);
    }

    private RequestContext context(HttpServletRequest request) {
        return requestContextFactory.from(request);
    }
}
