import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function formatDate(value: unknown) {
  if (!value) return "—";
  const date = new Date(String(value));
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

export function formatBytes(value: unknown) {
  const size = Number(value);
  if (!Number.isFinite(size)) return "—";
  if (size < 1024) return `${size} B`;
  if (size < 1024 ** 2) return `${(size / 1024).toFixed(1)} KB`;
  if (size < 1024 ** 3) return `${(size / 1024 ** 2).toFixed(1)} MB`;
  return `${(size / 1024 ** 3).toFixed(1)} GB`;
}

const DISPLAY_TEXT: Record<string, string> = {
  ACTIVE: "在线",
  ONLINE: "在线",
  VALID: "有效",
  INVALID: "失效",
  ENABLED: "已启用",
  OFFLINE: "离线",
  DISABLED: "已停用",
  PENDING_ASSIGNMENT: "待分配环境",
  ASSIGNED: "已分配",
  DRAFT: "草稿",
  APPROVED: "已批准",
  PENDING: "待处理",
  QUEUED: "排队中",
  DISPATCHED: "已下发",
  ACKED: "已确认",
  RUNNING: "执行中",
  PAUSED: "已暂停",
  STOPPING: "停止中",
  ATTACH_COMPLETED: "Attach 已完成",
  COMMAND_ENQUEUED: "命令已入队",
  SUCCESS: "成功",
  SUCCEEDED: "已成功",
  COMPLETED: "已完成",
  PARTIAL: "部分完成",
  FAILED: "失败",
  ERROR: "错误",
  CANCELLED: "已取消",
  EXPIRED: "已过期",
  ARCHIVED: "已归档",
  UNLOADING: "卸载中",
  UNLOADED: "已卸载",
  ABANDONED: "已废弃",
  PUBLISHED: "已发布",
  AVAILABLE: "可用",
  HEALTHY: "健康",
  MATCHED: "已匹配",
  BUILDING: "构建中",
  RETRYING: "重试中",
  READY: "就绪",
  LOW: "低风险",
  MEDIUM: "中风险",
  HIGH: "高风险",
  DEV: "dev",
  SIT: "sit",
  UAT: "uat",
  PROD: "prod",
  BEFORE: "调用前",
  RETURN: "正常返回后",
  THROWS: "抛出异常时",
  RULE_ROLLOUT: "规则发布",
  RULE_ROLLBACK: "规则卸载",
  RESET_CLASS: "恢复目标类原始字节码",
  RESET_ALL: "恢复全部原始字节码",
  OPERATION_PLAN: "发布计划",
  RULE: "规则",
  JAVA_METHOD: "Java 方法",
  USER: "用户",
  AGENT: "Agent",
  WAITING_AGENT: "等待 Agent",
  ALL_ACTIVE_INSTANCES: "全部在线实例",
  SEQUENTIAL: "顺序执行",
  PARALLEL: "并行执行",
  POSTGRESQL: "PostgreSQL",
  MYSQL: "MySQL",
  TEST_FIXTURE: "测试数据",
  MANUAL: "手动卸载",
  AGENT_GONE: "Agent 自动卸载",
  INSTANCE_GONE: "实例已消亡",
  ROLLOUT_FAILURE: "执行失败自动卸载",
  RULE_DELETION: "规则删除自动卸载",
};

const ACTION_TEXT: Record<string, string> = {
  RUNNING: "开始执行",
  COMPLETED: "完成发布",
  SUCCESS: "标记成功",
  FAILED: "标记失败",
  CANCELLED: "取消计划",
  EXPIRED: "标记过期",
  PAUSED: "暂停执行",
  UNLOADING: "开始卸载",
  UNLOADED: "标记已卸载",
  ABANDONED: "标记已废弃",
  UNLOAD: "卸载规则",
  UNLOAD_PLAN: "卸载所属计划",
};

const FIELD_TEXT: Record<string, string> = {
  id: "ID",
  application_id: "应用",
  application_environment: "应用",
  environment_id: "环境",
  environment_key: "环境",
  instance_id: "实例",
  agent_id: "Agent",
  operation_plan_id: "发布计划",
  rollback_execution_id: "卸载执行",
  rollback_type: "卸载方式",
  command_id: "Agent 命令",
  resource_id: "资源",
  resource_type: "资源类型",
  resource_version: "资源版本",
  plan_type: "计划类型",
  rule_id: "规则",
  rule_version_id: "规则版本",
  display_name: "用户名",
  approval_id: "审批",
  subject_id: "审批事项",
  subject_type: "审批事项类型",
  subject_version: "审批事项版本",
  status: "状态",
  version: "版本",
  name: "名称",
  nickname: "昵称",
  hostname: "主机名",
  application_name: "应用名称",
  project_name: "项目名称",
  environment_name: "环境",
  process_id: "进程 ID",
  process_start_id: "进程启动标识",
  runtime: "运行时",
  java_version: "Java 版本",
  load_mode: "加载方式",
  agent_version: "Agent 版本",
  instance_nickname: "实例快照",
  instance_last_seen_at: "实例最后心跳",
  attach_executor_id: "Attach 执行器",
  capabilities_json: "能力",
  labels_json: "标签",
  strategy_json: "发布策略",
  terminal_source: "终止来源",
  terminal_reason: "终止原因",
  automatic_rollback: "失败时自动卸载",
  automatic_unload: "失败时自动卸载",
  target_selector_json: "目标选择器",
  registration_status: "注册状态",
  last_seen_at: "最后在线时间",
  last_heartbeat_at: "最后心跳",
  lease_expires_at: "租约过期时间",
  created_at: "创建时间",
  updated_at: "更新时间",
  created_by: "创建人",
  updated_by: "更新人",
  requester: "申请人",
  reason: "原因",
  finished_at: "完成时间",
  subject_hash: "审批对象摘要",
  allowed_actions: "可执行操作",
};

export function fieldLabel(value: string) {
  const snake = value.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
  return FIELD_TEXT[snake] ?? snake.split("_").map((part) => DISPLAY_TEXT[part.toUpperCase()] ?? part).join(" ");
}

export function humanize(value: unknown) {
  if (value === null || value === undefined || value === "") return "—";
  if (typeof value === "boolean") return value ? "是" : "否";
  if (typeof value === "object") return JSON.stringify(localizeObject(value), null, 2);
  const text = String(value);
  const transitionReason = /^Web transition to ([A-Z_]+)$/.exec(text);
  if (transitionReason) {
    const target = transitionReason[1];
    return `通过 Web 控制台执行：${ACTION_TEXT[target] ?? DISPLAY_TEXT[target] ?? target}`;
  }
  return DISPLAY_TEXT[text.toUpperCase()] ?? text;
}

export function actionLabel(value: unknown) {
  if (value === null || value === undefined || value === "") return "执行操作";
  const text = String(value);
  return ACTION_TEXT[text.toUpperCase()] ?? humanize(text);
}

export function localizeObject(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(localizeObject);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [
      fieldLabel(key),
      localizeObject(item),
    ]));
  }
  if (typeof value === "string") return humanize(value);
  return value;
}

export function shortId(value: unknown) {
  const text = String(value ?? "");
  return text.length > 20 ? `${text.slice(0, 10)}…${text.slice(-6)}` : text || "—";
}
