package com.example.runtimemock.platform.api;

import com.example.runtimemock.platform.command.AgentCommandService;
import com.example.runtimemock.platform.recording.RecordingSessionCommandService;
import com.example.runtimemock.platform.service.PlatformJdbcService;
import com.example.runtimemock.platform.service.PlatformMaintenanceService;
import com.example.runtimemock.platform.service.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
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
@ConditionalOnProperty(prefix = "runtime-mock.platform.api", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public final class PlatformController {

    private final PlatformJdbcService service;
    private final AgentCommandService agentCommandService;
    private final PlatformMaintenanceService maintenanceService;
    private final RecordingSessionCommandService recordingSessionCommandService;
    private final RequestContextFactory requestContextFactory;

    public PlatformController(PlatformJdbcService service, AgentCommandService agentCommandService,
                              PlatformMaintenanceService maintenanceService,
                              RecordingSessionCommandService recordingSessionCommandService,
                              RequestContextFactory requestContextFactory) {
        this.service = service;
        this.agentCommandService = agentCommandService;
        this.maintenanceService = maintenanceService;
        this.recordingSessionCommandService = recordingSessionCommandService;
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

    @GetMapping("/rollout-batches")
    public List<Map<String, Object>> rolloutBatches() {
        return service.list("rollout_batch", "created_at, id");
    }

    @PostMapping("/operation-plans/{id}/batches")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createRolloutBatch(@PathVariable String id,
                                                  HttpServletRequest httpRequest,
                                                  @Valid @RequestBody Map<String, Object> request) {
        return service.createRolloutBatch(id, context(httpRequest), request);
    }

    @GetMapping("/rollout-executions")
    public List<Map<String, Object>> rolloutExecutions() {
        return service.list("rollout_instance_execution", "updated_at, id");
    }

    @PostMapping("/rollout-batches/{id}/executions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createRolloutExecution(@PathVariable String id,
                                                      HttpServletRequest httpRequest,
                                                      @Valid @RequestBody Map<String, Object> request) {
        return service.createRolloutExecution(id, context(httpRequest), request);
    }

    @GetMapping("/recording-rules")
    public List<Map<String, Object>> recordingRules() {
        return service.list("recording_rule", "created_at, id");
    }

    @PostMapping("/recording-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createRecordingRule(HttpServletRequest httpRequest,
                                                   @Valid @RequestBody Map<String, Object> request) {
        return service.createRecordingRule(context(httpRequest), request);
    }

    @GetMapping("/recording-rule-versions")
    public List<Map<String, Object>> recordingRuleVersions() {
        return service.list("recording_rule_version", "created_at, id");
    }

    @PostMapping("/recording-rules/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createRecordingRuleVersion(@PathVariable String id,
                                                          HttpServletRequest httpRequest,
                                                          @Valid @RequestBody Map<String, Object> request) {
        return service.createRecordingRuleVersion(id, context(httpRequest), request);
    }

    @GetMapping("/datasources")
    public List<Map<String, Object>> datasources() {
        return service.listDatasources();
    }

    @PostMapping("/datasources")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createDatasource(HttpServletRequest httpRequest,
                                                @Valid @RequestBody Map<String, Object> request) {
        return service.createDatasource(context(httpRequest), request);
    }

    @GetMapping("/extraction-templates")
    public List<Map<String, Object>> extractionTemplates() {
        return service.list("extraction_template", "created_at, id");
    }

    @PostMapping("/extraction-templates")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createExtractionTemplate(HttpServletRequest httpRequest,
                                                        @Valid @RequestBody Map<String, Object> request) {
        return service.createExtractionTemplate(context(httpRequest), request);
    }

    @GetMapping("/extraction-tasks")
    public List<Map<String, Object>> extractionTasks() {
        return service.list("extraction_task", "created_at, id");
    }

    @GetMapping("/extraction-executions")
    public List<Map<String, Object>> extractionExecutions() {
        return service.list("extraction_execution", "started_at, id");
    }

    @GetMapping("/extraction-results")
    public List<Map<String, Object>> extractionResults() {
        return service.list("extraction_result", "created_at, id");
    }

    @PostMapping("/extraction-tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createExtractionTask(HttpServletRequest httpRequest,
                                                    @Valid @RequestBody Map<String, Object> request) {
        return service.createExtractionTask(context(httpRequest), request);
    }

    @PostMapping("/extraction-tasks/{id}/transition")
    public Map<String, Object> transitionExtractionTask(@PathVariable String id,
                                                        HttpServletRequest httpRequest,
                                                        @Valid @RequestBody Map<String, Object> request) {
        return service.transitionExtractionTask(id, context(httpRequest), request);
    }

    @GetMapping("/replay-executions")
    public List<Map<String, Object>> replayExecutions() {
        return service.list("replay_execution", "created_at, id");
    }

    @GetMapping("/replay-batches")
    public List<Map<String, Object>> replayBatches() {
        return service.list("replay_batch", "started_at, id");
    }

    @GetMapping("/replay-invocation-results")
    public List<Map<String, Object>> replayInvocationResults() {
        return service.list("replay_invocation_result", "created_at, id");
    }

    @GetMapping("/comparison-results")
    public List<Map<String, Object>> comparisonResults() {
        return service.list("comparison_result", "created_at, id");
    }

    @PostMapping("/replay-executions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createReplayExecution(HttpServletRequest httpRequest,
                                                     @Valid @RequestBody Map<String, Object> request) {
        return service.createReplayExecution(context(httpRequest), request);
    }

    @PostMapping("/replay-executions/{id}/transition")
    public Map<String, Object> transitionReplayExecution(@PathVariable String id,
                                                         HttpServletRequest httpRequest,
                                                         @Valid @RequestBody Map<String, Object> request) {
        return service.transitionReplayExecution(id, context(httpRequest), request);
    }

    @GetMapping("/recording-sessions")
    public List<Map<String, Object>> recordingSessions() {
        return service.list("recording_session", "created_at, id");
    }

    @PostMapping("/recording-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createRecordingSession(HttpServletRequest httpRequest,
                                                      @Valid @RequestBody Map<String, Object> request) {
        return service.createRecordingSession(context(httpRequest), request);
    }

    @PostMapping("/recording-sessions/{id}/transition")
    public Map<String, Object> transitionRecordingSession(@PathVariable String id,
                                                          HttpServletRequest httpRequest,
                                                          @Valid @RequestBody Map<String, Object> request) {
        return recordingSessionCommandService.transition(id, context(httpRequest), request);
    }

    @GetMapping("/datasets")
    public List<Map<String, Object>> datasets() {
        return service.list("dataset_version", "created_at, id");
    }

    @PostMapping("/datasets")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createDataset(HttpServletRequest httpRequest,
                                             @Valid @RequestBody Map<String, Object> request) {
        return service.createDatasetVersion(context(httpRequest), request);
    }

    @GetMapping("/replay-plans")
    public List<Map<String, Object>> replayPlans() {
        return service.list("replay_plan", "created_at, id");
    }

    @PostMapping("/replay-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createReplayPlan(HttpServletRequest httpRequest,
                                                @Valid @RequestBody Map<String, Object> request) {
        return service.createReplayPlan(context(httpRequest), request);
    }

    @PostMapping("/replay-plans/{id}/transition")
    public Map<String, Object> transitionReplayPlan(@PathVariable String id,
                                                    HttpServletRequest httpRequest,
                                                    @Valid @RequestBody Map<String, Object> request) {
        return service.transitionReplayPlan(id, context(httpRequest), request);
    }

    @GetMapping("/approvals")
    public List<Map<String, Object>> approvals() {
        return service.list("approval_request", "created_at, id");
    }

    @PostMapping("/approvals")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createApproval(HttpServletRequest httpRequest,
                                              @Valid @RequestBody Map<String, Object> request) {
        return service.createApproval(context(httpRequest), request);
    }

    @PostMapping("/approvals/{id}/decisions")
    public Map<String, Object> decideApproval(@PathVariable String id,
                                              HttpServletRequest httpRequest,
                                              @Valid @RequestBody Map<String, Object> request) {
        return service.decideApproval(id, context(httpRequest), request);
    }

    @GetMapping("/audits")
    public List<Map<String, Object>> audits() {
        return service.audits();
    }

    @GetMapping("/outbox")
    public List<Map<String, Object>> outbox() {
        return service.outbox();
    }

    @GetMapping("/worker-artifacts")
    public List<Map<String, Object>> workerArtifacts() {
        return service.list("worker_artifact", "created_at, id");
    }

    private RequestContext context(HttpServletRequest request) {
        return requestContextFactory.from(request);
    }
}
