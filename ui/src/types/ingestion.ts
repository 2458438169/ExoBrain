import type { DocumentVO } from "../api/api.ts";

/**
 * 导入状态（共享契约 §6）：QUEUED → RUNNING → SUCCEEDED；临时失败 RETRY_WAIT 后重试，
 * 超过上限 FAILED；资源删除/权限撤销 CANCELLED。任务成功只表示“可用索引已发布”，
 * 上传返回 200 不等于可检索。
 */
export type IngestionStatus =
  | "QUEUED"
  | "RUNNING"
  | "RETRY_WAIT"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELLED";

/** GET /api/documents/{documentId}/ingestion 的响应；不含文件路径与服务商异常。 */
export interface IngestionJobView {
  jobId: string;
  documentId: string;
  kbId: string;
  status: IngestionStatus;
  attempts: number;
  maxAttempts: number;
  chunksDone: number;
  chunksTotal: number | null;
  errorCode: string | null;
  errorMessage: string | null;
  retryable: boolean;
  nextAttemptAt: string | null;
  finishedAt: string | null;
  indexRevisionId: string | null;
}

/** 文档列表里增量返回的导入字段；迁移前上传的旧文档没有任务，这些字段为空。 */
export interface DocumentIngestionFields {
  jobId?: string | null;
  indexStatus?: IngestionStatus | null;
  indexErrorCode?: string | null;
  indexErrorMessage?: string | null;
  chunksDone?: number | null;
  chunksTotal?: number | null;
}

export type DocumentWithIngestion = DocumentVO & DocumentIngestionFields;

/** 轮询退避（毫秒）：只在还有活动任务且页面可见时使用，不做每文档高频查询。 */
export const INGESTION_POLL_DELAYS_MS = [2000, 4000, 8000, 15000];

export function isIngestionActive(status?: string | null): boolean {
  return status === "QUEUED" || status === "RUNNING" || status === "RETRY_WAIT";
}

export interface IngestionStatusLabel {
  /** 列表里的中文状态：等待处理 / 正在索引 / 可检索 / 处理失败 / 已取消 */
  label: string;
  color: string;
  active: boolean;
  /** 失败时是否有可操作的重试（仍需服务端再次校验写权限） */
  retryable: boolean;
}

export function describeIngestionStatus(
  status?: string | null,
): IngestionStatusLabel | null {
  switch (status) {
    case "QUEUED":
      return { label: "等待处理", color: "default", active: true, retryable: false };
    case "RUNNING":
      return { label: "正在索引", color: "processing", active: true, retryable: false };
    case "RETRY_WAIT":
      return { label: "等待重试", color: "warning", active: true, retryable: false };
    case "SUCCEEDED":
      return { label: "可检索", color: "success", active: false, retryable: false };
    case "FAILED":
      return { label: "处理失败", color: "error", active: false, retryable: true };
    case "CANCELLED":
      return { label: "已取消", color: "default", active: false, retryable: false };
    default:
      return null;
  }
}

/**
 * 进度文案：只知道总量时显示真实的“已完成 x / 共 y 个片段”；
 * 不知道总量时只显示阶段，绝不生成虚假百分比。
 */
export function ingestionProgressText(document: DocumentIngestionFields): string {
  const stage = describeIngestionStatus(document.indexStatus);
  if (!stage) {
    return "—";
  }
  if (stage.label === "可检索") {
    return document.chunksTotal
      ? `已完成 ${document.chunksTotal} / ${document.chunksTotal} 个片段`
      : "已完成";
  }
  if (stage.label === "处理失败" || stage.label === "已取消") {
    return document.indexErrorMessage || stage.label;
  }
  if (document.chunksTotal && document.chunksTotal > 0) {
    return `${stage.label} · 已完成 ${document.chunksDone ?? 0} / ${document.chunksTotal} 个片段`;
  }
  return stage.label;
}
