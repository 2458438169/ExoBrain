import { get, post } from "./http.ts";
import type { RunDetailView, RunListResponse, RunStatus } from "../types/execution.ts";

/**
 * 05 执行追踪读取接口。路径相对当前构建的 API 基址（游客版是 /api/demo，账号版是 /admin/api），
 * 因此同一份代码在两套构建里都走各自的受权路由。
 *
 * silent：读取失败（HTML 404、JSON 404、500、断网、响应格式错误）由调用方决定如何呈现。
 * 调用方必须区分「成功的空列表」和「读取失败」，不能把失败当成没有记录。
 */
export function listSessionRuns(sessionId: string): Promise<RunListResponse> {
  return get<RunListResponse>(`/chat-sessions/${encodeURIComponent(sessionId)}/runs`, undefined, {
    silent: true,
  });
}

export function getRun(runId: string): Promise<RunDetailView> {
  return get<RunDetailView>(`/runs/${encodeURIComponent(runId)}`, undefined, { silent: true });
}

/**
 * 取消请求的返回（RunControlService.View）。
 * `cancelRequested` 只表示「停止请求已经登记」，运行终态必须以运行状态确认；
 * 已经进入终态的运行返回 ALREADY_TERMINAL，同样不能当作取消成功。
 */
export interface CancelRunResponse {
  runId: string;
  status: RunStatus;
  cancelRequested: boolean;
  message: string;
}

export function cancelRun(runId: string): Promise<CancelRunResponse> {
  return post<CancelRunResponse>(`/runs/${encodeURIComponent(runId)}/cancel`, undefined, {
    silent: true,
  });
}
