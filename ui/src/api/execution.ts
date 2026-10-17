import { get } from "./http.ts";
import type { RunDetailView, RunListResponse } from "../types/execution.ts";

/**
 * 05 执行追踪读取接口。路径相对当前构建的 API 基址（游客版是 /api/demo，账号版是 /admin/api），
 * 因此同一份代码在两套构建里都走各自的受权路由。
 *
 * silent：老版本后端还没有这些路由时，历史记录照常显示，只是没有执行详情，不弹错误提示。
 */
export function listSessionRuns(sessionId: string): Promise<RunListResponse> {
  return get<RunListResponse>(`/chat-sessions/${encodeURIComponent(sessionId)}/runs`, undefined, {
    silent: true,
  });
}

export function getRun(runId: string): Promise<RunDetailView> {
  return get<RunDetailView>(`/runs/${encodeURIComponent(runId)}`, undefined, { silent: true });
}