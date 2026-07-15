package com.example.kairo.platform.service;

import com.example.kairo.platform.persistence.mapper.RuleChainStateMapper;
import com.example.kairo.api.ChainDesiredState;
import com.example.kairo.api.RuleChainRevision;
import com.example.kairo.api.ReconcileResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * Platform-side desired/actual rule-chain state (&sect;3.1 / &sect;3.2 / &sect;4.3).
 *
 * <p>Holds the desired {@code RuleChainSpec} per (application, environment, agent, target)
 * behind an optimistic CAS on the monotonic {@code revision}: a publish that believes the
 * current revision is {@code N} only lands when the stored revision is still {@code N}, so
 * out-of-order or concurrent publishes are fenced at the database. Agent acknowledgements
 * upsert the instance state (applied revision/hash), and {@link #reconcile} compares the
 * desired and actual revision/hash to drive convergence.
 */
@Service
public class RuleChainStateService {

    private final RuleChainStateMapper stateMapper;
    private final BusinessIdService businessIdService;
    private final Clock clock;

    @Autowired
    public RuleChainStateService(RuleChainStateMapper stateMapper, BusinessIdService businessIdService) {
        this(stateMapper, businessIdService, Clock.systemUTC());
    }

    RuleChainStateService(RuleChainStateMapper stateMapper, BusinessIdService businessIdService, Clock clock) {
        this.stateMapper = stateMapper;
        this.businessIdService = businessIdService;
        this.clock = clock;
    }

    /**
     * Atomically advance the desired chain revision via CAS. Returns {@code true} when the
     * expected revision matched and the new revision was written; {@code false} when a stale
     * or concurrent publish lost the CAS (the caller must refresh and retry).
     */
    @Transactional
    public boolean casDesiredChain(String applicationId, String environmentId, String agentId, String chainId,
                                   String targetClassName, String targetMethodName, String targetMethodDescriptor,
                                   String targetLocation, String targetCallSiteSelectorJson,
                                   long expectedRevision, long newRevision, String canonicalHash,
                                   ChainDesiredState desiredState, long transformationRevision,
                                   String ruleEntriesJson, String actor) {
        Map<String, Object> current = stateMapper.findDesiredState(applicationId, environmentId, agentId, chainId);
        Instant now = clock.instant();
        if (current == null) {
            if (expectedRevision != 0L) {
                return false; // caller believed a chain existed; it did not
            }
            String id = businessIdService.nextId("rule_chain_desired_state",
                    "chain:" + agentId + ":" + chainId);
            stateMapper.insertDesiredState(id, applicationId, environmentId, agentId, chainId,
                    targetClassName, targetMethodName, targetMethodDescriptor, targetLocation,
                    targetCallSiteSelectorJson, newRevision, canonicalHash, desiredState.name(),
                    transformationRevision, ruleEntriesJson, actor, timestamp(now));
            return true;
        }
        long currentRevision = ((Number) current.get("revision")).longValue();
        if (currentRevision != expectedRevision) {
            return false; // stale: the chain moved since the caller last read it
        }
        long version = ((Number) current.get("version")).longValue();
        int updated = stateMapper.casDesiredRevision(String.valueOf(current.get("id")),
                expectedRevision, newRevision, canonicalHash, desiredState.name(),
                transformationRevision, ruleEntriesJson, actor, timestamp(now), version);
        return updated == 1;
    }

    /** Record an Agent's applied chain state (the actual state). */
    @Transactional
    public void recordInstanceAck(String desiredStateId, String agentId, RuleChainRevision applied,
                                  long transformationRevision, String transformationHash,
                                  String degradedReason, String status) {
        Instant now = clock.instant();
        String id = businessIdService.nextId("rule_chain_instance_state",
                "instance:" + desiredStateId + ":" + agentId);
        stateMapper.upsertInstanceState(id, desiredStateId, agentId, applied.value(), applied.hash(),
                transformationRevision, transformationHash, timestamp(now), degradedReason, status, timestamp(now));
    }

    /** Reconcile desired vs actual for one agent+target. */
    public ReconcileResult reconcile(String applicationId, String environmentId, String agentId, String chainId,
                                     RuleChainRevision desired) {
        Map<String, Object> desiredRow = stateMapper.findDesiredState(applicationId, environmentId, agentId, chainId);
        if (desiredRow == null) {
            return ReconcileResult.unknown(desired);
        }
        Map<String, Object> instance = stateMapper.findInstanceState(String.valueOf(desiredRow.get("id")), agentId);
        if (instance == null) {
            return ReconcileResult.unknown(desired);
        }
        long actualRevision = ((Number) instance.get("applied_revision")).longValue();
        String actualHash = String.valueOf(instance.get("applied_hash"));
        RuleChainRevision actual = new RuleChainRevision(actualRevision, actualHash);
        if (actualRevision == desired.value() && actualHash.equals(desired.hash())) {
            return ReconcileResult.inSync(actual, desired);
        }
        if (actualRevision < desired.value()) {
            return ReconcileResult.behind(actual, desired);
        }
        return ReconcileResult.aheadOrDiverged(actual, desired);
    }

    /**
     * V1.6 &sect;3: describe the desired + actual rule-chain state for a target
     * (the {@code /rule-chains} read resource).
     */
    public Map<String, Object> describe(String applicationId, String environmentId, String agentId, String chainId) {
        Map<String, Object> desired = stateMapper.findDesiredState(applicationId, environmentId, agentId, chainId);
        if (desired == null) {
            throw PlatformException.notFound("rule-chain",
                    applicationId + "/" + environmentId + "/" + agentId + "/" + chainId);
        }
        Map<String, Object> instance = stateMapper.findInstanceState(String.valueOf(desired.get("id")), agentId);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("desired", desired);
        result.put("instance", instance);
        return result;
    }

    /**
     * V1.6 &sect;3: reconcile using the persisted desired revision (the
     * {@code /reconciliations} resource), so a caller need only name the target.
     */
    public ReconcileResult reconcileCurrent(String applicationId, String environmentId, String agentId, String chainId) {
        Map<String, Object> desiredRow = stateMapper.findDesiredState(applicationId, environmentId, agentId, chainId);
        if (desiredRow == null) {
            throw PlatformException.notFound("rule-chain",
                    applicationId + "/" + environmentId + "/" + agentId + "/" + chainId);
        }
        long desiredRevision = ((Number) desiredRow.get("revision")).longValue();
        String desiredHash = String.valueOf(desiredRow.get("canonical_hash"));
        return reconcile(applicationId, environmentId, agentId, chainId,
                new RuleChainRevision(desiredRevision, desiredHash));
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
