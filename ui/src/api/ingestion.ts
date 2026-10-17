import { get, post } from "./http.ts";
import type { IngestionJobView } from "../types/ingestion.ts";

/**
 * 导入状态查询：服务端按 KB 读权限校验（公开只读文档也允许游客读取状态）。
 * 轮询请配合 useDocuments 的退避与可见页面限制，不要逐文档高频请求。
 */
export async function getDocumentIngestion(
  documentId: string,
  signal?: AbortSignal,
): Promise<IngestionJobView> {
  return get<IngestionJobView>(
    `/documents/${encodeURIComponent(documentId)}/ingestion`,
    undefined,
    { signal },
  );
}

/**
 * 重试导入：服务端按 KB 写权限校验（公开只读文档会被拒绝），并且是幂等的——
 * 已有排队/执行中的任务时直接返回它，不会产生并行任务。
 */
export async function retryDocumentIngestion(
  documentId: string,
): Promise<IngestionJobView> {
  return post<IngestionJobView>(
    `/documents/${encodeURIComponent(documentId)}/ingestion/retry`,
  );
}
