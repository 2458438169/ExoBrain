import { useEffect, useRef, useState, useCallback } from "react";
import { getDocumentsByKbId, deleteDocument } from "../api/api.ts";
import { retryDocumentIngestion } from "../api/ingestion.ts";
import {
  INGESTION_POLL_DELAYS_MS,
  isIngestionActive,
  type DocumentWithIngestion,
  type IngestionJobView,
} from "../types/ingestion.ts";
import { showRequestError } from "../api/http.ts";

export function useDocuments(kbId: string | undefined) {
  const [documents, setDocuments] = useState<DocumentWithIngestion[]>([]);
  const [loading, setLoading] = useState(false);
  /** 退避档位在多次刷新之间保留，避免每次 setState 都把间隔重置成 2s。 */
  const pollStep = useRef(0);

  const fetchDocuments = useCallback(async () => {
    if (!kbId) {
      setDocuments([]);
      return;
    }

    setLoading(true);
    try {
      const resp = await getDocumentsByKbId(kbId);
      setDocuments(resp.documents);
    } finally {
      setLoading(false);
    }
  }, [kbId]);

  useEffect(() => {
    void fetchDocuments().catch((error) =>
      showRequestError(error, "文档加载失败，请稍后刷新"),
    );
  }, [fetchDocuments]);

  const hasActiveIngestion = documents.some((document) =>
    isIngestionActive(document.indexStatus),
  );

  /**
   * 导入中的文档用退避轮询（2s → 4s → 8s → 15s 封顶），页面不可见时完全不发请求，
   * 路由切换或全部任务结束即取消。文档列表一次请求带全部状态，不逐文档轮询。
   */
  useEffect(() => {
    if (!kbId || !hasActiveIngestion) {
      pollStep.current = 0;
      return;
    }
    let cancelled = false;
    let timer: number | undefined;
    const schedule = () => {
      const delay =
        INGESTION_POLL_DELAYS_MS[
          Math.min(pollStep.current, INGESTION_POLL_DELAYS_MS.length - 1)
        ];
      timer = window.setTimeout(() => {
        if (cancelled) return;
        if (typeof document !== "undefined" && document.visibilityState !== "visible") {
          schedule();
          return;
        }
        pollStep.current += 1;
        void fetchDocuments()
          .catch(() => {
            // 轮询失败保持上一次已知状态，下一次按更长的间隔重试。
          })
          .finally(() => {
            if (!cancelled) schedule();
          });
      }, delay);
    };
    schedule();
    const onVisible = () => {
      if (document.visibilityState === "visible" && !cancelled) {
        void fetchDocuments().catch(() => undefined);
      }
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      cancelled = true;
      if (timer !== undefined) window.clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [kbId, hasActiveIngestion, fetchDocuments]);

  const deleteDocumentHandle = async (documentId: string) => {
    await deleteDocument(documentId);
    await fetchDocuments();
  };

  /** 失败后重试；服务端会再校验 KB 写权限并保证不会并行生成第二个任务。 */
  const retryIngestionHandle = async (
    documentId: string,
  ): Promise<IngestionJobView> => {
    const job = await retryDocumentIngestion(documentId);
    pollStep.current = 0;
    await fetchDocuments();
    return job;
  };

  return {
    documents,
    loading,
    refreshDocuments: fetchDocuments,
    deleteDocument: deleteDocumentHandle,
    retryIngestion: retryIngestionHandle,
  };
}
