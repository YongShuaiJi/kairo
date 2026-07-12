package com.example.kairo.platform.api;

import com.example.kairo.api.ScriptCompilationResult;
import com.example.kairo.platform.script.ScriptSessionService;
import com.example.kairo.platform.service.RbacService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code POST /api/v1/scripts/compile} (§3.6).
 *
 * <p>Compiles a script against an agent's target ClassLoader, returning the structured
 * {@link ScriptCompilationResult} (profile, policy revision, compiler version, target loader id and
 * diagnostics). The platform computes the effective tier and dispatches a {@code SCRIPT_COMPILE}
 * command; the agent performs the real compile and reports diagnostics. The script source is carried
 * in the in-memory exchange only, never persisted.
 */
@RestController
@RequestMapping("/api/v1/scripts")
@ConditionalOnProperty(prefix = "kairo.platform.api", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public final class ScriptCompileController {

    private final ScriptSessionService service;
    private final RequestContextFactory requestContextFactory;
    private final RbacService rbacService;

    public ScriptCompileController(ScriptSessionService service,
                                   RequestContextFactory requestContextFactory,
                                   RbacService rbacService) {
        this.service = service;
        this.requestContextFactory = requestContextFactory;
        this.rbacService = rbacService;
    }

    @PostMapping("/compile")
    public ScriptCompilationResult compile(HttpServletRequest httpRequest,
                                           @RequestBody Map<String, Object> request) {
        return service.compile(requestContextFactory.from(httpRequest), request);
    }
}
