# Kairo 文档索引

## 产品规划

- [V1.X 版本迭代路线图](./roadmap/v1.x-version-roadmap.md)：V1.1 至 V1.7 LTS 的版本范围、实施顺序、交付物和验收标准。
- [V1.X 技术改造方案](./roadmap/v1.x-technical/README.md)：V1.1 至 V1.6 的模块改造、技术决策、测试方案及多智能体交付规范。
- [V1.X 多智能体开发与验收规范](./roadmap/v1.x-technical/development-and-acceptance.md)：开发波次、评审检查点、交付和验收模板。

## 开发者文档

- [平台技术使用文档](./developer/platform-technical-guide.md)：环境搭建、模块边界、产品设计、代码设计、实例注册、Agent 设计和规则发布设计。
- [简化平台架构](./architecture/simplified-platform-architecture.md)：当前 V1 控制面和 Agent 架构。
- [模块边界治理](./architecture/module-boundary-governance.md)：模块职责和保留理由。
- [Web 设计说明](./architecture/kairo-platform-web-design.md)：前端结构和交互设计。

## 用户文档

- [平台用户使用文档](./user-guide/platform-complete-user-guide.md)：登录、账户、实例、Agent、规则开发、发布和卸载。
- [Groovy 规则脚本编写手册](./user-guide/rule-script-authoring-guide.md)：脚本 API、安全限制、经典场景和复杂 Demo。
- [故障注入指南](./user-guide/platform-fault-injection-guide.md)：故障注入操作说明。

## 运维与发布

- [Docker 本地平台文档](./ops/platform-docker.md)：Compose 启停和本地演示。
- [生产发布级检查清单](./ops/production-readiness-checklist.md)：发布前质量、权限、Token、Agent、脚本和文档检查项。
- [V1.7 LTS 运维 Runbook](./ops/v1.7-lts-runbook.md)：V1.7 九类事件应急流程、固定指标契约与受控演练入口。
- [诊断日志规范](./ops/diagnostic-logging.md)：全链路事件、关联字段、脱敏边界、P7D 证据和排障路径。

## API 文档

- [OpenAPI](./api/platform-openapi.yaml)：Platform API 描述。
- [权限矩阵](./api/permission-matrix.md)：超级管理员和业务用户能力边界。
- [错误码](./api/error-codes.md)：API 错误码说明。

## 需求与软著

- [产品需求](./requirements/kairo-product-requirements.md)：当前产品需求和功能边界。
- [软著申请材料草案](./copyright/kairo-software-copyright-application.md)：申请表填报草案、软件说明、源程序和文档鉴别材料建议。
