# Permission Matrix

| Capability | Main Resources | Default Roles |
| --- | --- | --- |
| `OBSERVE` | health, list APIs, audit read | PlatformAdmin, Operator, Auditor |
| `INSTANCE_MANAGE` | instances and labels | PlatformAdmin, Operator |
| `AGENT_MANAGE` | sidecars, agents, heartbeats, manual agent commands | PlatformAdmin |
| `RULE_MANAGE` | rules, rule versions, rollout-bound rule metadata | PlatformAdmin, Operator |
| `ROLLOUT_MANAGE` | operation plans, rollout batches, rollout executions | PlatformAdmin, Operator |
| `RECORD_ARGUMENTS` | recording rules, recording sessions | PlatformAdmin, Operator |
| `RECORD_RETURN` | return-value recording capability | PlatformAdmin, Operator |
| `DATA_EXTRACT` | datasources, extraction templates, extraction tasks | PlatformAdmin, Operator |
| `IMPORT_TO_TEST` | dataset versions and replay plans | PlatformAdmin, Operator |
| `REPLAY_EXECUTE` | replay executions | PlatformAdmin, Operator |
| `EXPORT_DATA` | export/download authorization surfaces | PlatformAdmin |
| `RESET` | reset/shutdown operations | PlatformAdmin |
| `APPROVE` | approval requests and decisions | PlatformAdmin |
| `ADMIN` | all capabilities | PlatformAdmin |

Fencing-token issuance is authorized by the target resource type: rule tokens require `RULE_MANAGE`, operation tokens require `ROLLOUT_MANAGE`, extraction tokens require `DATA_EXTRACT`, replay execution tokens require `REPLAY_EXECUTE`, and unknown resource types require `ADMIN`.

Agent command poll/ack accepts either an actor matching the agent id with `X-Identity-Source: agent`, or a platform actor with `AGENT_MANAGE`.

All write APIs create hash-chained audit records and outbox events. Production high-risk writes should be routed through approval requests before rollout execution.
