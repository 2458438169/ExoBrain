/**
 * 05 执行追踪的前端类型（与共享契约 §5 的读取 API 对应）。
 *
 * 账号接口返回完整投影（含内部标识）；游客接口只返回白名单字段，因此这里把它们声明为可选，
 * 界面不得用缺失字段推断配置或补齐耗时。
 */
export type RunStatus =
  | "QUEUED"
  | "RUNNING"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELLED"
  | "INTERRUPTED";

export type RunEventType =
  | "MODEL_CALL"
  | "MODEL_RETRY"
  | "TOOL_CALL"
  | "RETRIEVAL"
  | "PERSIST_MESSAGE"
  | "STEP_LIMIT"
  | "ERROR";

export type RunEventStatus = "SUCCEEDED" | "FAILED" | "CANCELLED";

export interface RunEventView {
  eventId?: string;
  sequence: number;
  type: RunEventType;
  status: RunEventStatus;
  startedAt: string;
  durationMs: number;
  toolCallId?: string | null;
  toolName?: string | null;
  retrievalId?: string | null;
  retryAttempt?: number | null;
  errorCode?: string | null;
  detail?: string | null;
}

export interface RunSummaryView {
  runId: string;
  schemaVersion?: number;
  sessionId?: string;
  triggeringMessageId?: string | null;
  answerMessageId?: string | null;
  agentId?: string | null;
  agentName?: string | null;
  agentConfigHash?: string | null;
  modelCatalogId?: string | null;
  modelName?: string | null;
  status: RunStatus;
  interrupted: boolean;
  submittedAt: string;
  startedAt?: string | null;
  finishedAt?: string | null;
  durationMs?: number | null;
  queueDurationMs?: number | null;
  inputTokens?: number | null;
  outputTokens?: number | null;
  totalTokens?: number | null;
  errorCode?: string | null;
  traceIncomplete?: boolean;
  heartbeatAt?: string | null;
}

export interface RunDetailView {
  summary: RunSummaryView;
  events: RunEventView[];
}

export interface RunListResponse {
  runs: RunSummaryView[];
}

/**
 * 消息 metadata 的 05 增量字段：服务端写入的 runId。
 *
 * 共享类型文件 `types/index.ts` 仍由其他任务共同维护，这里以扩展类型声明可选 `runId`
 * （后端已写入；旧消息没有该字段）。
 */
export type ChatMessageMetadataWithRun = import("./index.ts").ChatMessageVOMetadata & {
  runId?: string | null;
};