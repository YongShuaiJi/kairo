package com.example.kairo.platform.api;

import com.example.kairo.platform.command.AgentCommandService;
import com.example.kairo.platform.attach.AttachExecutorCommandService;
import com.example.kairo.platform.attach.PlatformAgentLifecycleService;
import com.example.kairo.platform.rollout.RuleUnloadService;
import com.example.kairo.platform.service.PlatformException;
import com.example.kairo.platform.service.PlatformCoreService;
import com.example.kairo.platform.service.PlatformMaintenanceService;
import com.example.kairo.platform.service.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "kairo.platform.api", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public final class PlatformController {

    private final PlatformCoreService service;
    private final AgentCommandService agentCommandService;
    private final AttachExecutorCommandService attachExecutorCommandService;
    private final PlatformMaintenanceService maintenanceService;
    private final PlatformAgentLifecycleService agentLifecycleService;
    private final RuleUnloadService ruleUnloadService;
    private final RequestContextFactory requestContextFactory;

    public PlatformController(PlatformCoreService service, AgentCommandService agentCommandService,
                              AttachExecutorCommandService attachExecutorCommandService,
                              PlatformMaintenanceService maintenanceService,
                              PlatformAgentLifecycleService agentLifecycleService,
                              RuleUnloadService ruleUnloadService,
                              RequestContextFactory requestContextFactory) {
        this.service = service;
        this.agentCommandService = agentCommandService;
        this.attachExecutorCommandService = attachExecutorCommandService;
        this.maintenanceService = maintenanceService;
        this.agentLifecycleService = agentLifecycleService;
        this.ruleUnloadService = ruleUnloadService;
        this.requestContextFactory = requestContextFactory;
    }

    @GetMapping("/control/health")
    public Map<String, Object> health() {
        return service.health();
    }

    @PostMapping("/control/schedulers/run-once")
    public Map<String, Object> runSchedulersOnce(HttpServletRequest httpRequest) {
        return maintenanceService.runOnce(context(httpRequest));
    }

    @GetMapping("/fencing-tokens")
    public List<Map<String, Object>> fencingTokens() {
        return service.listFencingTokens();
    }

    @PostMapping("/fencing-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> issueFencingToken(HttpServletRequest httpRequest,
                                                 @Valid @RequestBody Map<String, Object> request) {
        return service.issueFencingToken(context(httpRequest), request);
    }

    @GetMapping("/instances")
    public List<Map<String, Object>> instances() {
        return service.list("instance", "created_at, id");
    }

    @PostMapping("/instances")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createInstance(HttpServletRequest httpRequest,
                                              @Valid @RequestBody Map<String, Object> request) {
        return service.createInstance(context(httpRequest), request);
    }

    @PostMapping("/agent-registrations/self")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> registerAgentRuntime(HttpServletRequest httpRequest,
                                                    @Valid @RequestBody Map<String, Object> request) {
        return service.registerAgentRuntime(context(httpRequest), request);
    }

    @PostMapping("/attach-sidecars/self")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> registerAttachSidecar(HttpServletRequest httpRequest,
                                                     @Valid @RequestBody Map<String, Object> request) {
        return service.registerAttachSidecar(context(httpRequest), request);
    }

    @PostMapping("/attach-executors/{id}/heartbeat")
    public Map<String, Object> heartbeatAttachExecutor(@PathVariable String id,
                                                       HttpServletRequest httpRequest,
                                                       @Valid @RequestBody Map<String, Object> request) {
        return attachExecutorCommandService.heartbeat(id, context(httpRequest), request);
    }

    @PostMapping("/attach-executors/{id}/commands/next")
    public Map<String, Object> pollNextAttachExecutorCommand(@PathVariable String id,
                                                             HttpServletRequest httpRequest,
                                                             @Valid @RequestBody Map<String, Object> request) {
        return attachExecutorCommandService.pollNext(id, context(httpRequest), request);
    }

    @PostMapping("/attach-executor-commands/{id}/ack")
    public Map<String, Object> ackAttachExecutorCommand(@PathVariable String id,
                                                        HttpServletRequest httpRequest,
                                                        @Valid @RequestBody Map<String, Object> request) {
        return attachExecutorCommandService.ack(id, context(httpRequest), request);
    }

    @PostMapping("/instances/{id}/environment")
    public Map<String, Object> assignInstanceEnvironment(@PathVariable String id,
                                                         HttpServletRequest httpRequest,
                                                         @Valid @RequestBody Map<String, Object> request) {
        return service.assignInstanceEnvironment(id, context(httpRequest), request);
    }

    @PatchMapping("/instances/{id}/nickname")
    public Map<String, Object> updateInstanceNickname(@PathVariable String id,
                                                      HttpServletRequest httpRequest,
                                                      @Valid @RequestBody Map<String, Object> request) {
        return service.updateInstanceNickname(id, context(httpRequest), request);
    }

    @PostMapping("/instances/{id}/agent/attach")
    public Map<String, Object> attachAgent(@PathVariable String id,
                                           HttpServletRequest httpRequest,
                                           @Valid @RequestBody Map<String, Object> request) {
        return agentLifecycleService.attach(context(httpRequest), id, request);
    }

    @PostMapping("/instances/{id}/agent/deactivate")
    public Map<String, Object> deactivateAgent(@PathVariable String id,
                                               HttpServletRequest httpRequest,
                                               @Valid @RequestBody Map<String, Object> request) {
        return agentLifecycleService.deactivate(context(httpRequest), id, request);
    }

    @PostMapping("/instances/{id}/agent/reload")
    public Map<String, Object> reloadAgent(@PathVariable String id,
                                           HttpServletRequest httpRequest,
                                           @Valid @RequestBody Map<String, Object> request) {
        return agentLifecycleService.reload(context(httpRequest), id, request);
    }

    @GetMapping("/sidecars")
    public List<Map<String, Object>> sidecars() {
        return service.list("sidecar_instance", "created_at, id");
    }

    @PostMapping("/sidecars")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createSidecar(HttpServletRequest httpRequest,
                                             @Valid @RequestBody Map<String, Object> request) {
        return service.createSidecar(context(httpRequest), request);
    }

    @GetMapping("/agents")
    public List<Map<String, Object>> agents() {
        return service.listAgents();
    }

    @PostMapping("/agents")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createAgent(HttpServletRequest httpRequest,
                                           @Valid @RequestBody Map<String, Object> request) {
        return service.createAgent(context(httpRequest), request);
    }

    @PostMapping("/agents/{id}/heartbeat")
    public Map<String, Object> recordAgentHeartbeat(@PathVariable String id,
                                                    HttpServletRequest httpRequest,
                                                    @Valid @RequestBody Map<String, Object> request) {
        return service.recordAgentHeartbeat(id, context(httpRequest), request);
    }

    @GetMapping("/agent-commands")
    public List<Map<String, Object>> agentCommands() {
        return agentCommandService.listCommands();
    }

    @PostMapping("/agents/{id}/commands")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createAgentCommand(@PathVariable String id,
                                                  HttpServletRequest httpRequest,
                                                  @Valid @RequestBody Map<String, Object> request) {
        return agentCommandService.createManualCommand(context(httpRequest), id, request);
    }

    @PostMapping("/agents/{id}/commands/next")
    public Map<String, Object> pollNextAgentCommand(@PathVariable String id,
                                                    HttpServletRequest httpRequest,
                                                    @Valid @RequestBody Map<String, Object> request) {
        return agentCommandService.pollNext(id, context(httpRequest), request);
    }

    @PostMapping("/agent-commands/{id}/ack")
    public Map<String, Object> ackAgentCommand(@PathVariable String id,
                                               HttpServletRequest httpRequest,
                                               @Valid @RequestBody Map<String, Object> request) {
        return agentCommandService.ack(id, context(httpRequest), request);
    }

    @GetMapping("/rules")
    public List<Map<String, Object>> rules() {
        return service.list("rule", "created_at, id");
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createRule(HttpServletRequest httpRequest,
                                          @Valid @RequestBody Map<String, Object> request) {
        return service.createRule(context(httpRequest), request);
    }

    @DeleteMapping("/rules/{id}")
    public Map<String, Object> deleteRule(@PathVariable String id,
                                          HttpServletRequest httpRequest) {
        throw PlatformException.methodNotAllowed("RULE_MANUAL_DELETE_DISABLED",
                "规则不支持手动删除，请先停用规则，系统将在保留期结束后自动删除");
    }

    @PostMapping("/rules/{id}/disable")
    public Map<String, Object> disableRule(@PathVariable String id,
                                           HttpServletRequest httpRequest) {
        return service.disableRule(id, context(httpRequest));
    }

    @PostMapping("/rules/{id}/enable")
    public Map<String, Object> enableRule(@PathVariable String id,
                                          HttpServletRequest httpRequest) {
        return service.enableRule(id, context(httpRequest));
    }

    @PostMapping("/rules/{id}/versions/{version}/disable")
    public Map<String, Object> disableRuleVersion(@PathVariable String id,
                                                  @PathVariable long version,
                                                  HttpServletRequest httpRequest) {
        RequestContext context = context(httpRequest);
        Map<String, Object> unload = ruleUnloadService.unloadRuleForDeletion(id, version, context);
        Map<String, Object> ruleVersion = service.disableRuleVersion(id, version, context);
        return Map.of("unload", unload, "ruleVersion", ruleVersion);
    }

    @PostMapping("/rules/{id}/versions/{version}/enable")
    public Map<String, Object> enableRuleVersion(@PathVariable String id,
                                                 @PathVariable long version,
                                                 HttpServletRequest httpRequest) {
        return service.enableRuleVersion(id, version, context(httpRequest));
    }

    @GetMapping("/rule-versions")
    public List<Map<String, Object>> ruleVersions() {
        return service.list("rule_version", "created_at, id");
    }

    @PostMapping("/rules/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createRuleVersion(@PathVariable String id,
                                                 HttpServletRequest httpRequest,
                                                 @Valid @RequestBody Map<String, Object> request) {
        return service.createRuleVersion(id, context(httpRequest), request);
    }

    @DeleteMapping("/rules/{id}/versions/{version}")
    public Map<String, Object> deleteRuleVersion(@PathVariable String id,
                                                 @PathVariable long version,
                                                 HttpServletRequest httpRequest) {
        throw PlatformException.methodNotAllowed("RULE_VERSION_MANUAL_DELETE_DISABLED",
                "规则版本不支持手动删除，请停用规则，系统将在保留期结束后自动删除");
    }

    @PostMapping("/rules/{id}/versions/delete")
    public Map<String, Object> deleteRuleVersions(@PathVariable String id,
                                                  HttpServletRequest httpRequest,
                                                  @Valid @RequestBody Map<String, Object> request) {
        throw PlatformException.methodNotAllowed("RULE_VERSION_MANUAL_DELETE_DISABLED",
                "规则版本不支持手动删除，请停用规则，系统将在保留期结束后自动删除");
    }

    @GetMapping("/operation-plans")
    public List<Map<String, Object>> operationPlans() {
        return service.list("operation_plan", "created_at, id");
    }

    @PostMapping("/operation-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createOperationPlan(HttpServletRequest httpRequest,
                                                   @Valid @RequestBody Map<String, Object> request) {
        return service.createOperationPlan(context(httpRequest), request);
    }

    @PostMapping("/operation-plans/{id}/transition")
    public Map<String, Object> transitionOperationPlan(@PathVariable String id,
                                                       HttpServletRequest httpRequest,
                                                       @Valid @RequestBody Map<String, Object> request) {
        return service.transitionOperationPlan(id, context(httpRequest), request);
    }

    @PostMapping("/operation-plans/{id}/unload")
    public Map<String, Object> unloadOperationPlan(@PathVariable String id,
                                                  HttpServletRequest httpRequest,
                                                  @Valid @RequestBody Map<String, Object> request) {
        return ruleUnloadService.unload(id, context(httpRequest), request);
    }

    @GetMapping("/rollout-executions")
    public List<Map<String, Object>> rolloutExecutions() {
        return service.list("rollout_instance_execution", "updated_at, id");
    }

    private RequestContext context(HttpServletRequest request) {
        return requestContextFactory.from(request);
    }
}
