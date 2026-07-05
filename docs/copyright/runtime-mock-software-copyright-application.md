# Runtime Mock 软件著作权申请材料草案

本文用于准备“Runtime Mock”软件著作权登记材料。申请前需要把占位字段替换为真实信息，并以中国版权保护中心或所在地软件登记初审窗口的最新要求为准。

参考依据：

- 国家版权局发布的《计算机软件著作权登记办法》相关规则说明。
- 北京市政务服务“计算机软件著作权登记初审”页面对申请材料、源程序和文档鉴别材料的说明。

## 1. 申请表填报草案

| 字段 | 建议填写 |
| --- | --- |
| 软件全称 | Runtime Mock Java 运行时故障注入控制平台 |
| 软件简称 | Runtime Mock |
| 版本号 | V1.0 |
| 软件分类 | 应用软件 |
| 开发完成日期 | 待填写，建议填写当前 V1 功能闭环完成日期 |
| 首次发表日期 | 未发表或待填写 |
| 开发方式 | 原始取得 |
| 权利取得方式 | 原始取得 |
| 权利范围 | 全部权利 |
| 著作权人 | 待填写公司或个人全称 |
| 运行环境 | JDK 21、PostgreSQL、Redis、Docker、Node.js 20、现代浏览器 |
| 开发语言 | Java、TypeScript、Groovy、SQL |
| 开发工具 | Maven、Spring Boot、MyBatis、Next.js、React、Tailwind CSS、Docker Compose |
| 软件用途 | 面向内部开发、测试和稳定性工程场景，对 Java 运行时方法进行可控故障注入、规则发布和快速卸载恢复 |

## 2. 软件功能说明

Runtime Mock Java 运行时故障注入控制平台是一套面向 Java 应用的内部测试和稳定性验证工具。系统通过 Java Instrumentation 和 Byte Buddy 在目标 JVM 中加载 Agent，通过平台控制面管理应用实例、Agent、规则版本、发布计划和卸载恢复流程，通过 Groovy 脚本描述方法级故障注入规则。

主要功能：

1. 应用实例注册：Agent 启动后自动上报项目、应用、环境、主机、进程、Java 版本、加载方式和能力信息。
2. Agent 诊断：展示 Agent 在线状态、监听地址、版本、心跳和命令执行情况。
3. 规则管理：支持选择目标方法、编辑 Groovy 脚本、服务端校验、试运行、保存规则版本和启停版本。
4. 发布管理：支持创建发布计划，将指定规则版本发布到目标环境在线实例，并记录实例执行结果。
5. 卸载恢复：对已发布规则下发卸载命令，清理规则注册并恢复目标类字节码。
6. 用户权限：内置超级管理员和业务用户两类权限，超级管理员管理用户和 Token，业务用户执行业务操作。
7. Token 管理：用户 Token 可创建、更换、续期和删除；一个用户只保留一个有效 Token；Token 明文只展示一次。
8. 本地演示：提供 demo 应用、attach-executor、Platform Server 和 Web 控制台的一键 Compose 演示环境。

## 3. 技术特点

- JVM 运行时增强：通过 Java Instrumentation 和 Byte Buddy Advice 对目标方法进行运行时拦截。
- 隔离加载：bootstrap agent 负责轻量入口，现代 core jar 通过隔离 ClassLoader 加载，减少对业务应用依赖空间的影响。
- 脚本化决策：通过预编译 Groovy 脚本返回 `proceed`、`return`、`throw` 三类决策。
- 控制面分离：Platform Server 负责状态管理和命令调度，Agent 在被测 JVM 内执行规则应用和卸载。
- 可回滚发布：发布计划绑定确定规则版本，支持按实例记录执行状态和卸载恢复。
- 简化权限模型：内部系统采用超级管理员和业务用户两级权限，降低运维复杂度。
- 安全脚本边界：默认禁用循环、反射、线程、文件、网络、进程和 ClassLoader 等高风险能力。

## 4. 系统结构

### 4.1 后端模块

- `runtime-mock-bootstrap-api`：被增强业务方法可访问的桥接 API。
- `runtime-mock-api`：规则脚本公共 API。
- `runtime-mock-object`：JSON 转换、属性路径访问和运行时对象构造。
- `runtime-mock-groovy`：Groovy 编译、脚本缓存和安全策略。
- `runtime-mock-core`：规则注册表、调度器、采样、fail-open 和重入保护。
- `runtime-mock-agent-core`：Byte Buddy transformer 和方法 Advice。
- `runtime-mock-agent-server`：Agent 本地 HTTP API、状态查询和 Platform 命令轮询。
- `runtime-mock-agent-bootstrap`：premain 和 agentmain 入口。
- `runtime-mock-attach-cli`：动态 attach 命令行工具。
- `runtime-mock-sidecar`：attach executor 与运行时辅助边界。
- `runtime-mock-platform-server`：Spring Boot 控制面 API。
- `runtime-mock-demo`：演示应用。

### 4.2 前端模块

- `runtime-mock-platform-web/app`：Next.js 路由和 BFF。
- `runtime-mock-platform-web/components/layout`：应用外壳、导航、主题和用户菜单。
- `runtime-mock-platform-web/components/resource`：通用资源列表、详情和表单。
- `runtime-mock-platform-web/components/editor`：规则工作台。
- `runtime-mock-platform-web/components/settings`：账户设置和用户管理。
- `runtime-mock-platform-web/lib/api`：API client、DTO 类型和字段工具。

## 5. 运行流程

1. Java 应用通过 premain 或 attach 加载 Agent。
2. Agent 向 Platform 注册应用、环境、实例和自身能力。
3. 用户在 Web 控制台创建规则，选择目标方法并编写 Groovy 脚本。
4. Platform 校验脚本并保存规则版本。
5. 用户创建发布计划，调度器生成 Agent 命令。
6. Agent 拉取命令，将规则应用到目标方法。
7. 业务请求进入目标方法时触发脚本，返回继续执行、替换返回值或抛出异常的决策。
8. 用户执行卸载，Agent 清理规则并恢复目标类字节码。

## 6. 源代码鉴别材料建议

软件著作权登记通常需要提交源程序鉴别材料。一般做法是提交源程序前、后各连续 30 页；不足 60 页时提交全部。常见要求还包括源程序每页不少于 50 行、A4 单面黑白、页码和软件名称版本一致。

建议选取能够体现本软件核心创新的代码，避免只选配置、样式或生成文件：

前 30 页建议从以下文件开始连续截取：

1. `runtime-mock-agent-bootstrap/src/main/java/com/example/runtimemock/agent/bootstrap/RuntimeMockAgent.java`
2. `runtime-mock-agent-core/src/main/java/com/example/runtimemock/agent/core/AgentRuntime.java`
3. `runtime-mock-agent-core/src/main/java/com/example/runtimemock/agent/core/RuntimeMockTransformer.java`
4. `runtime-mock-core/src/main/java/com/example/runtimemock/core/RuleDispatcher.java`
5. `runtime-mock-groovy/src/main/java/com/example/runtimemock/groovy/GroovyScriptCompiler.java`

后 30 页建议从以下文件连续截取：

1. `runtime-mock-platform-server/src/main/java/com/example/runtimemock/platform/auth/AccessTokenService.java`
2. `runtime-mock-platform-server/src/main/java/com/example/runtimemock/platform/service/PlatformCoreService.java`
3. `runtime-mock-platform-server/src/main/java/com/example/runtimemock/platform/command/AgentCommandService.java`
4. `runtime-mock-platform-web/components/editor/rule-workbench.tsx`
5. `runtime-mock-platform-web/components/resource/resource-page.tsx`

排版建议：

- 页眉写“Runtime Mock Java 运行时故障注入控制平台 V1.0”。
- 右上角标页码。
- 删除空白行和无意义注释。
- 不包含密钥、Token、账号、内部地址和个人信息。
- 不包含 `node_modules`、`target`、`.next`、构建产物和生成文件。

## 7. 文档鉴别材料建议

文档鉴别材料可以选用本项目使用说明书或设计说明书。建议以以下文件合并整理为“Runtime Mock Java 运行时故障注入控制平台使用说明书 V1.0”：

1. `docs/developer/platform-technical-guide.md`
2. `docs/user-guide/platform-complete-user-guide.md`
3. `docs/user-guide/rule-script-authoring-guide.md`
4. `docs/ops/platform-docker.md`
5. `docs/architecture/simplified-platform-architecture.md`

文档建议结构：

1. 软件概述。
2. 运行环境。
3. 安装部署。
4. 功能模块。
5. 用户权限。
6. 实例注册。
7. Agent 注册。
8. 规则开发。
9. Groovy 脚本规则。
10. 规则发布和卸载。
11. 常见问题。

常见格式要求：

- 文档前、后各连续 30 页；不足 60 页时提交全部。
- 文档每页不少于 30 行。
- A4 单面黑白打印。
- 页眉软件名称和版本号与申请表保持一致。
- 页码清晰，材料不要装订错误。

## 8. 提交前检查

- 软件名称、简称和版本号在申请表、源程序页眉、文档页眉中完全一致。
- 著作权人名称与营业执照、身份证或其他证明文件一致。
- 开发完成日期、首次发表日期、权利取得方式真实一致。
- 源程序和文档页码连续。
- 源程序不包含敏感凭据。
- 文档内容与当前 V1 功能一致，不写未实现功能。
- 如果委托代理机构办理，另行准备委托授权文件。

## 9. 可直接用于说明书摘要的文本

Runtime Mock Java 运行时故障注入控制平台是一套面向 Java 应用的内部测试和稳定性验证软件。系统通过 Java Agent 在目标 JVM 中实现方法级运行时拦截，通过平台控制面完成应用实例注册、Agent 诊断、规则脚本开发、规则版本管理、发布计划执行和卸载恢复。用户可使用受限 Groovy 脚本定义故障注入逻辑，模拟异常、特殊返回值、参数改写和异常降级等场景，用于验证业务系统的容错、降级和恢复能力。平台内置简化权限模型和 Token 管理机制，适合研发、测试和稳定性工程团队在内部环境中快速开展故障演练。
