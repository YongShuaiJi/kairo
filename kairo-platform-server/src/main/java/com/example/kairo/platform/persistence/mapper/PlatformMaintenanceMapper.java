package com.example.kairo.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;

public interface PlatformMaintenanceMapper {

    int expireAgents(@Param("now") Timestamp now);

    int expireExecutors(@Param("now") Timestamp now);

    int expireExecutorTargets(@Param("now") Timestamp now);

    int expireSidecars(@Param("now") Timestamp now);

    int expireInstances(@Param("now") Timestamp now);

    int deleteCompletedAttachCommands(@Param("cutoff") Timestamp cutoff);

    int deleteCompletedAgentCommands(@Param("cutoff") Timestamp cutoff);

    int deleteOfflineAgentHeartbeats(@Param("cutoff") Timestamp cutoff);

    int deleteOfflineAgentCapabilities(@Param("cutoff") Timestamp cutoff);

    int deleteOfflineAgentRegistrations(@Param("cutoff") Timestamp cutoff);

    int deleteOfflineDegradedClasses(@Param("cutoff") Timestamp cutoff);

    int deleteOfflineAgents(@Param("cutoff") Timestamp cutoff);

    int deleteOfflineSidecars(@Param("cutoff") Timestamp cutoff);

    int deleteOfflineAttachTargets(@Param("cutoff") Timestamp cutoff);

    int deleteOfflineAttachExecutors(@Param("cutoff") Timestamp cutoff);

    int deleteOfflineRuleRuntimeStatuses(@Param("cutoff") Timestamp cutoff);

    int deleteOfflineRuleInstanceBindings(@Param("cutoff") Timestamp cutoff);

    int deleteOfflineInstanceLabels(@Param("cutoff") Timestamp cutoff);

    int deleteOfflineAssetClaims(@Param("cutoff") Timestamp cutoff);

    int archiveOfflineInstances(@Param("cutoff") Timestamp cutoff);

    int markPlansUnloadedWhenAgentsGone(@Param("now") Timestamp now);

    int markRuntimeStatusesRemovedForAgentGonePlans(@Param("now") Timestamp now);

    int markPlansAbandonedWhenInstancesGone(@Param("cutoff") Timestamp cutoff);

    int markExecutionsAbandonedWhenInstancesGone(@Param("cutoff") Timestamp cutoff);
}
