# Permission Matrix

| Capability | Main Resources | Default Roles |
| --- | --- | --- |
| `OBSERVE` | health, list APIs, audit read | PlatformAdmin, Operator, Auditor |
| `INSTANCE_MANAGE` | instances and labels | PlatformAdmin, Operator |
| `AGENT_MANAGE` | sidecars, agents, heartbeats, manual agent commands | PlatformAdmin |
| `RULE_MANAGE` | rules, rule versions, rollout-bound rule metadata | PlatformAdmin, Operator |
| `ROLLOUT_MANAGE` | operation plans, rollout executions, unload executions | PlatformAdmin, Operator |
| `ADMIN` | all capabilities | PlatformAdmin |

Fencing-token issuance is authorized by the target resource type: rule tokens require `RULE_MANAGE`, operation tokens require `ROLLOUT_MANAGE`, Agent tokens require `AGENT_MANAGE`, and unknown resource types require `ADMIN`.

Agent command poll/ack accepts either an actor matching the agent id with `X-Identity-Source: agent`, or a platform actor with `AGENT_MANAGE`.

All write APIs create hash-chained audit records. Publishing is controlled by operation-plan state transitions and fencing tokens.
