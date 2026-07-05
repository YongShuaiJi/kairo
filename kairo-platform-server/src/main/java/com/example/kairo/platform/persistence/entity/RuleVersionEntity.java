package com.example.kairo.platform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("rule_version")
public class RuleVersionEntity {

    @TableId
    private String id;
    private String ruleId;
    private Long version;
    private String status;
    private String riskLevel;
    private String matcherJson;
    private String scriptHash;
    private String scriptJson;
    private String governanceJson;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime disabledAt;
    private LocalDateTime autoDeleteAt;
    private String disabledFromStatus;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }

    public String getMatcherJson() { return matcherJson; }
    public void setMatcherJson(String matcherJson) { this.matcherJson = matcherJson; }

    public String getScriptHash() { return scriptHash; }
    public void setScriptHash(String scriptHash) { this.scriptHash = scriptHash; }

    public String getScriptJson() { return scriptJson; }
    public void setScriptJson(String scriptJson) { this.scriptJson = scriptJson; }

    public String getGovernanceJson() { return governanceJson; }
    public void setGovernanceJson(String governanceJson) { this.governanceJson = governanceJson; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getDisabledAt() { return disabledAt; }
    public void setDisabledAt(LocalDateTime disabledAt) { this.disabledAt = disabledAt; }

    public LocalDateTime getAutoDeleteAt() { return autoDeleteAt; }
    public void setAutoDeleteAt(LocalDateTime autoDeleteAt) { this.autoDeleteAt = autoDeleteAt; }

    public String getDisabledFromStatus() { return disabledFromStatus; }
    public void setDisabledFromStatus(String disabledFromStatus) { this.disabledFromStatus = disabledFromStatus; }
}
