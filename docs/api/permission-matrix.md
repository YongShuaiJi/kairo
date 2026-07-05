# Runtime Mock 权限矩阵

当前 V1 是内部系统，权限模型只保留两类用户：

- 超级管理员：系统最高权限，可以做所有业务操作和用户管理操作。
- 业务用户：可以做实例、Agent、规则、发布等业务操作，不能管理用户。

| Capability | 主要资源 | 超级管理员 | 业务用户 |
| --- | --- | --- | --- |
| `ADMIN` | 系统管理、Token 管理、维护接口 | 是 | 否 |
| `USER_MANAGE` | 创建用户、续期用户 Token、强制更换用户 Token、删除用户 | 是 | 否 |
| `INSTANCE_MANAGE` | 实例、环境、实例标签 | 是 | 是 |
| `AGENT_MANAGE` | Agent、attach-executor、心跳、手工 Agent 命令 | 是 | 是 |
| `RULE_MANAGE` | 规则、规则版本、脚本工作台 | 是 | 是 |
| `ROLLOUT_MANAGE` | 发布计划、实例执行、卸载恢复 | 是 | 是 |

账户与 Token 规则：

- 所有用户都可以修改自己的用户名。
- 所有用户都可以更换自己的 Token，更换后旧 Token 立即失效。
- 用户不能给自己的 Token 续期。
- 只有超级管理员可以给其他用户续期 Token。
- 一个用户有且只能有一个有效 Token；创建或更换 Token 时会删除旧 Token。
- 权限判断使用稳定用户 ID，不使用可变用户名作为判断键。

Agent 命令轮询和回执接受两类身份：

- `identitySource = agent` 且 actor 与 agent id 匹配。
- 平台用户拥有 `AGENT_MANAGE` 能力。

所有写 API 应创建审计记录。发布由 operation-plan 状态和 fencing token 控制。
