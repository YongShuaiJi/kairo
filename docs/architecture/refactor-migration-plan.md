# 简化架构迁移计划

## 已完成

- 新增对象存储 SPI 和 MinIO 适配器。
- Worker 产物从本地文件改为对象存储。
- 对象上传前使用本地 KEK + 随机 DEK 做信封加密。
- 新增数据库哈希存储的用户/Agent Bearer Token。
- 增加全局 API 认证过滤器，移除生产模式对身份请求头的信任。
- 同一平台镜像支持 API 与 Worker 两种独立运行角色。
- Docker Compose 移除 Keycloak，新增独立 Worker 服务。

## 后续阶段

### 阶段 2：对象生命周期

- Dataset 级 DEK 策略和密钥销毁状态；
- Multipart upload；
- 对象保留期和清理任务；
- MinIO versioning 与可选 Object Lock；
- 阿里云 OSS 首个云适配器。

### 阶段 3：认证体验

- 管理员登录和密码哈希（如确有 Web 登录需求）；
- Refresh Token 或短期会话；
- Agent enrollment token；
- Token 校验 Redis 缓存；
- 可选 OIDC identity provider。

### 阶段 4：生产验证

- Docker Compose 端到端 smoke；
- MinIO 故障、Kafka 重试和 Redis 丢失测试；
- JDK 8/11/17/21 Agent 矩阵；
- 性能、长稳和容量测试。

## 兼容性说明

- `header-dev` 模式保留给现有 MockMvc 和本机开发测试。
- Agent 已支持 `platformToken`，可直接切换为平台签发的 Agent Token。
- 数据库迁移只新增 `platform_access_token`，不会破坏现有业务表。
- 原本已经生成的 `file:` Worker 产物不会自动迁移，需要单独导入 MinIO。
