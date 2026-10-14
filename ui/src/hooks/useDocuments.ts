import { useEffect, useState, useCallback } from "react";
import {
  type DocumentVO,
  getDocumentsByKbId,
  deleteDocument,
} from "../api/api.ts";
import { showRequestError } from "../api/http.ts";

export function useDocuments(kbId: string | undefined) {
  const [documents, setDocuments] = useState<DocumentVO[]>([]);
  const [loading, setLoading] = useState(false);

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
    void fetchDocuments().catch((error) => showRequestError(error, "文档加载失败，请稍后刷新"));
  }, [fetchDocuments]);

  const deleteDocumentHandle = async (documentId: string) => {
    await deleteDocument(documentId);
    await fetchDocuments();
  };

  return {
    documents,
    loading,
    refreshDocuments: fetchDocuments,
    deleteDocument: deleteDocumentHandle,
  };
}

