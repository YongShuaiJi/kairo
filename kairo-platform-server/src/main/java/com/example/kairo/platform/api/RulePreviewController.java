package com.example.kairo.platform.api;

import com.example.kairo.platform.api.dto.RulePreviewRequest;
import com.example.kairo.platform.api.dto.RulePreviewResponse;
import com.example.kairo.platform.service.RequestContext;
import com.example.kairo.platform.service.RulePreviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * V1.6 &sect;5.3 canonical rule preview/assembly API. The platform owns the rule
 * business defaults (status, risk, capabilities, target/matcher shape) and returns
 * the exact typed payload the caller should persist, plus a preview token/revision,
 * structured impact/risk, the script validation result and revert guidance. The web
 * workbench and AI clients call this instead of assembling the payload client-side.
 */
@RestController
@RequestMapping("/api/v1/rules")
@ConditionalOnProperty(prefix = "kairo.platform.api", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public final class RulePreviewController {

    private final RulePreviewService rulePreviewService;
    private final RequestContextFactory requestContextFactory;

    public RulePreviewController(RulePreviewService rulePreviewService,
                                 RequestContextFactory requestContextFactory) {
        this.rulePreviewService = rulePreviewService;
        this.requestContextFactory = requestContextFactory;
    }

    @PostMapping("/preview")
    public RulePreviewResponse preview(@RequestBody RulePreviewRequest request,
                                       HttpServletRequest httpRequest) {
        return rulePreviewService.preview(context(httpRequest), request);
    }

    private RequestContext context(HttpServletRequest request) {
        return requestContextFactory.from(request);
    }
}
