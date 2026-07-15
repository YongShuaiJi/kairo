package com.example.kairo.platform.api;

import com.example.kairo.api.operation.Operation;
import com.example.kairo.api.operation.OperationEvent;
import com.example.kairo.api.paging.Page;
import com.example.kairo.platform.operation.OperationService;
import com.example.kairo.platform.service.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Unified Operation resource API (V1.6 &sect;2.2 / &sect;5.1). Exposes the
 * long-running operations converged by {@link OperationService} for polling by
 * Web, CLI, SDK and MCP clients.
 */
@RestController
@RequestMapping("/api/v1/operations")
@ConditionalOnProperty(prefix = "kairo.platform.api", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public final class OperationController {

    private final OperationService operationService;
    private final RequestContextFactory requestContextFactory;

    public OperationController(OperationService operationService,
                               RequestContextFactory requestContextFactory) {
        this.operationService = operationService;
        this.requestContextFactory = requestContextFactory;
    }

    @GetMapping("/{id}")
    public Operation get(@PathVariable String id, HttpServletRequest request) {
        context(request);
        return operationService.get(id);
    }

    @GetMapping("/{id}/events")
    public List<OperationEvent> events(@PathVariable String id, HttpServletRequest request) {
        context(request);
        return operationService.events(id);
    }

    @GetMapping
    public Page<Operation> list(@RequestParam(required = false) String status,
                                @RequestParam(required = false, defaultValue = "50") int limit,
                                @RequestParam(required = false) String resourceType,
                                @RequestParam(required = false) String resourceId,
                                HttpServletRequest request) {
        context(request);
        List<Operation> items;
        if (resourceType != null && !resourceType.isBlank() && resourceId != null && !resourceId.isBlank()) {
            items = operationService.listByResource(resourceType, resourceId);
        } else {
            items = operationService.list(status, limit);
        }
        return Page.of(items);
    }

    private RequestContext context(HttpServletRequest request) {
        return requestContextFactory.from(request);
    }
}
