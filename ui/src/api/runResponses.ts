import type { RunDetailView, RunEventView, RunListResponse, RunSummaryView } from "../types/execution.ts";

const RUN_STATUSES = new Set(["QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED"]);
const EVENT_TYPES = new Set(["MODEL_CALL", "MODEL_RETRY", "TOOL_CALL", "RETRIEVAL", "PERSIST_MESSAGE", "STEP_LIMIT", "ERROR"]);
const EVENT_STATUSES = new Set(["SUCCEEDED", "FAILED", "CANCELLED"]);
const record = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === "object" && !Array.isArray(value);
const optionalText = (value: unknown) => value == null || typeof value === "string";
const optionalNumber = (value: unknown) => value == null || (typeof value === "number" && Number.isFinite(value));

function summary(value: unknown): value is RunSummaryView {
  if (!record(value) || typeof value.runId !== "string" || !value.runId ||
    typeof value.status !== "string" || !RUN_STATUSES.has(value.status) ||
    typeof value.interrupted !== "boolean" || typeof value.submittedAt !== "string") return false;
  return ["sessionId", "triggeringMessageId", "answerMessageId", "agentId", "agentName", "agentConfigHash",
    "modelCatalogId", "modelName", "startedAt", "finishedAt", "errorCode", "heartbeatAt"].every((key) => optionalText(value[key])) &&
    ["schemaVersion", "durationMs", "queueDurationMs", "inputTokens", "outputTokens", "totalTokens"].every((key) => optionalNumber(value[key])) &&
    (value.traceIncomplete == null || typeof value.traceIncomplete === "boolean");
}

function event(value: unknown): value is RunEventView {
  if (!record(value) || typeof value.sequence !== "number" || !Number.isFinite(value.sequence) ||
    typeof value.type !== "string" || !EVENT_TYPES.has(value.type) ||
    typeof value.status !== "string" || !EVENT_STATUSES.has(value.status) ||
    typeof value.startedAt !== "string" || typeof value.durationMs !== "number" || !Number.isFinite(value.durationMs)) return false;
  return ["eventId", "toolCallId", "toolName", "retrievalId", "errorCode", "detail"].every((key) => optionalText(value[key])) &&
    optionalNumber(value.retryAttempt);
}

// Validate entries too: an array containing null must stay a recoverable read
// failure, rather than reaching React and removing the whole chat page.
export function readRunList(value: unknown): RunListResponse {
  if (!record(value) || !Array.isArray(value.runs) || !value.runs.every(summary)) {
    throw new Error("执行记录响应格式无效，请重试");
  }
  return { runs: value.runs };
}

export function readRunDetail(value: unknown, requestedRunId: string): RunDetailView {
  if (!record(value) || !summary(value.summary) || value.summary.runId !== requestedRunId ||
    !Array.isArray(value.events) || !value.events.every(event)) {
    throw new Error("执行详情响应格式无效，请重试");
  }
  return { summary: value.summary, events: value.events };
}
