package com.example.runtimemock.platform.service;

import com.example.runtimemock.platform.rollout.RolloutExecutor;
import com.example.runtimemock.platform.worker.ExtractionWorker;
import com.example.runtimemock.platform.worker.ReplayWorker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PlatformMaintenanceService {

    private final RbacService rbacService;
    private final ObjectProvider<RolloutExecutor> rolloutExecutor;
    private final ObjectProvider<ExtractionWorker> extractionWorker;
    private final ObjectProvider<ReplayWorker> replayWorker;

    public PlatformMaintenanceService(RbacService rbacService,
                                      ObjectProvider<RolloutExecutor> rolloutExecutor,
                                      ObjectProvider<ExtractionWorker> extractionWorker,
                                      ObjectProvider<ReplayWorker> replayWorker) {
        this.rbacService = rbacService;
        this.rolloutExecutor = rolloutExecutor;
        this.extractionWorker = extractionWorker;
        this.replayWorker = replayWorker;
    }

    public Map<String, Object> runOnce(RequestContext context) {
        rbacService.require(context, "ADMIN");
        Map<String, Object> result = new LinkedHashMap<>();
        RolloutExecutor rollout = rolloutExecutor.getIfAvailable();
        result.put("rollout", rollout == null ? Map.of("status", "DISABLED") : rollout.runOnce(context));
        ExtractionWorker extraction = extractionWorker.getIfAvailable();
        result.put("extraction", extraction == null ? Map.of("status", "DISABLED") : extraction.runOnce(context));
        ReplayWorker replay = replayWorker.getIfAvailable();
        result.put("replay", replay == null ? Map.of("status", "DISABLED") : replay.runOnce(context));
        return result;
    }
}
