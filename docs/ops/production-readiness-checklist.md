# Kairo 生产发布级检查清单

本文用于判断当前迭代是否具备阶段性发布条件。检查目标不是扩大功能范围，而是确认 V1 故障注入闭环在工程结构、权限、数据、发布、回滚和文档层面可以被内部团队稳定使用。

## 1. 发布范围

当前 V1 发布范围：

- Java 进程通过 premain、agentmain 或 attach 加载 Kairo Agent。
- Agent 自动注册应用、环境、实例和自身能力。
- Web 控制台查看实例、Agent、规则、发布计划和执行结果。
- 用户编写 Groovy 故障注入脚本，完成校验、试运行、保存版本。
- 超级管理员创建用户、续期用户 Token、更换用户 Token、删除用户。
- 普通业务用户可执行实例、Agent、规则、发布相关业务操作，不能管理用户。
- 发布规则到在线实例，支持卸载恢复。

当前明确不在范围内：

- 生产流量录制、数据集提取、回放和审批流。
- 多租户计费、组织架构、SSO/OIDC。
- Kafka Outbox、MinIO、Vault、云 KMS、Kubernetes 原生编排。
- 规则脚本任意 Groovy 能力开放。

## 2. 必过验证

每次发布前至少执行：

```bash
mvn -pl kairo-platform-server -am test -DskipITs

cd kairo-platform-web
npm run typecheck
npm run lint
npm run build

cd ..
./scripts/platform-up.sh
./scripts/platform-smoke.sh
./scripts/platform-down.sh
```

如果要验证完整 JVM 注入链路，再执行：

```bash
mvn test
```

## 3. 权限与 Token

- 超级管理员拥有最高权限，可以创建用户、续期用户 Token、强制更换用户 Token、删除用户和执行所有业务操作。
- 普通业务用户不能看到用户管理菜单，不能调用用户管理 API。
- 所有用户只能修改自己的用户名和更换自己的 Token，不能给自己续期。
- 一个用户有且只能有一个有效 Token；创建或更换 Token 时必须删除旧 Token。
- 当前会话身份使用稳定用户 ID 判断，不能用可变用户名作为权限键。
- Token 明文只在创建或更换时展示一次，后端只保存哈希。

## 4. 数据与迁移

- 所有表结构变更必须通过 Flyway migration 提交。
- 迁移脚本必须支持空库初始化和已有库升级。
- 默认 `app-default`、`env-dev` 只作为历史迁移兼容，不作为新接入应用的固定标识。
- 用户 Token 的 `subject_id` 必须存储稳定用户 ID。
- 删除用户时必须同时清理该用户 Token、外部身份和角色绑定。

## 5. Agent 与发布安全

- Agent 本地 API 必须要求 `X-Agent-Token` 或 `Authorization: Bearer`。
- attach-executor 与被测 demo 在同一运行边界内，Platform 不直接进入目标 JVM。
- 发布计划必须指定确定的规则版本。
- 发布失败或实例离线时不能误报成功。
- 卸载应恢复目标类字节码；实例离线时需要等恢复在线后再次卸载。
- 脚本运行要 fail-open，脚本异常不能拖垮目标 JVM。

## 6. Groovy 脚本安全

当前生产级默认限制：

- 禁用循环、反射、线程、文件、网络、进程、ClassLoader、metaClass 和系统退出。
- 限制脚本体积、行数、嵌套深度、集合大小和字符串字面量大小。
- 所有脚本必须返回 `mock.proceed()`、`mock.returnValue(...)`、`mock.returnJson(...)` 或 `mock.throwException(...)`。

这些限制不是功能缺失，而是当前 V1 安全边界。需要放开时必须先补隔离执行、超时控制、权限白名单、审计和灰度开关。

## 7. 前端工程质量

- 资源页、规则页、用户管理页不能使用解释型占位文案替代真实操作入口。
- 普通业务用户不可见用户管理菜单。
- 账户与设置必须放在右上角用户菜单中。
- 日期选择器默认不展开，点击输入框后弹出。
- 详情弹窗避免大面积空白，字段布局应紧凑、可读。
- API DTO 字段必须通过共享工具兼容 snake_case 和 camelCase。

## 8. 后端工程质量

- Controller 只负责 HTTP 入参、上下文和响应装配。
- Service 承担业务规则，权限由 `RbacService` 统一判断。
- Mapper 只承担 SQL，不在 XML 中隐藏业务分支。
- 幂等写操作必须传入 idempotency key。
- 错误响应必须使用统一 `PlatformException` 和错误码。

## 9. 发布结论模板

当以下条件全部满足时，可判定“阶段性生产发布级”：

- 核心自动化验证全部通过。
- 用户权限和 Token 行为符合需求。
- Agent 注册、规则保存、发布、命中、卸载闭环通过 smoke。
- 文档覆盖开发搭建、用户操作、Groovy 规则、生产运维和软著材料。
- 无已知 P0/P1 缺陷。

如果存在任一 P0/P1 缺陷，应先阻断发布。P2 缺陷可以带入发布说明，但必须有规避方式和修复计划。

## 10. 相关文档

- [V1.7 LTS 运维 Runbook](./v1.7-lts-runbook.md)：发布后的九类事件应急流程、固定指标契约与受控演练入口，供发布后运维对照执行。
