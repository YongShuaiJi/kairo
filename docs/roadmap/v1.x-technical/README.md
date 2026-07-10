# Kairo V1.X 技术改造方案索引

本目录把 [V1.X 版本迭代路线图](../v1.x-version-roadmap.md) 转换为可直接交给开发智能体执行的技术方案。
路线图定义“做什么”，本目录定义“基于当前代码怎样做、怎样提交证据、由谁验收”。

## 方案列表

| 版本 | 技术方案 | 前置条件 |
| --- | --- | --- |
| V1.1 | [增强可视化与运行时底座](./v1.1-bytecode-visibility.md) | V1.0 |
| V1.2 | [脚本能力全面开放](./v1.2-script-capabilities.md) | V1.1 核心契约 |
| V1.3 | [扩展增强位置](./v1.3-enhancement-locations.md) | V1.1、V1.2 |
| V1.4 | [多规则增强链与可靠恢复](./v1.4-rule-chain-recovery.md) | V1.1～V1.3 |
| V1.5 | [现代 JVM 场景兼容](./v1.5-modern-jvm-compatibility.md) | V1.4 |
| V1.6 | [API First 与 AI First](./v1.6-api-ai-first.md) | V1.4 稳定，V1.5 契约冻结 |

## 所有智能体必须遵守的冻结契约

开发前先完成一次契约评审。以下概念只能由负责集成的主智能体统一修改，其他智能体不得在分支中
自行创建同义模型：

- `ClassIdentity`：`binaryClassName + classLoaderId`，后续可附加 module、code source 和字节码哈希；
- `MethodIdentity`：`ClassIdentity + methodName + JVM descriptor`；
- `EnhancementLocation`：方法阶段、构造器阶段或调用点位置；
- `EnhancementTarget`：方法身份与增强位置的组合；
- `TransformationRevision`：Agent 内目标类转换状态的单调修订号；
- `RuleChainRevision`：平台期望的规则链版本及规范化内容哈希；
- `CapabilityProfile`：`SAFE`、`EXTENDED`、`UNRESTRICTED`；
- `AgentCommandEnvelope`：协议版本、命令 ID、幂等键、期望修订号、载荷和截止时间；
- `OperationResult`：状态、实际修订号、字节码哈希、诊断、可重试性和恢复建议。

上述契约建议分别落在：

- Agent 与 Platform 共用的线协议 DTO：`kairo-api` 或新建的轻量 `kairo-protocol` 模块；
- 目标 JVM 内执行模型：`kairo-core`；
- 字节码实现：`kairo-agent-core`；
- 脚本策略：`kairo-groovy`；
- 权威状态、任务和审计：`kairo-platform-server`。

不得让 `kairo-platform-server` 直接依赖 Byte Buddy、Groovy 实现类或目标 JVM 的反射对象。

## 智能体交付规范

每个开发智能体交付时必须提供：

1. 与本文档范围对应的实现说明，列出实际修改与明确未完成项；
2. 数据库迁移、协议字段和 API 变更清单；
3. 自动化测试命令及完整结果；
4. 至少一个真实 JVM 端到端证据；
5. 性能或内存影响说明；
6. 风险、兼容性和回滚方式；
7. 文档和 OpenAPI 更新；
8. 不夹带其他版本的大规模重构。

## 验收方式

主验收智能体按三层验收：

- **契约验收**：模型、协议、错误语义和版本策略是否符合冻结契约；
- **功能验收**：逐条执行版本技术方案中的测试和场景；
- **集成验收**：与已经完成版本联合运行，检查增强、卸载、API 和数据库升级无回归。

仅有单元测试通过不算完成。Agent 相关功能必须在独立测试 JVM 中验证，涉及字节码恢复时必须比较
实际 class bytes 或规范化字节码结构。

## 并行开发约束

- V1.1 的模型和快照底座先合入；V1.2、V1.3 才能并行推进实现。
- V1.4 的规则链和恢复语义由一个智能体统一负责核心模型，冲突分析和共存测试可并行。
- V1.5 与 V1.6 可在 V1.4 稳定后并行，但 V1.6 不得提前冻结尚未稳定的底层语义。
- 所有数据库迁移在合入前重新编号，开发分支不要假定迁移序号永久有效。
- 所有共享文件修改必须保持小步提交，避免同时重写 `AgentRuntime`、`AgentHttpServer` 或平台大控制器。
