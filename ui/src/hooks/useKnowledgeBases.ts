import { useEffect, useState } from "react";
import {
  createKnowledgeBase,
  type CreateKnowledgeBaseRequest,
  getKnowledgeBases,
} from "../api/api.ts";
import type { KnowledgeBase } from "../types";
import { showRequestError } from "../api/http.ts";
import { knowledgeCatalogEvents } from "./catalogEvents.ts";

export function useKnowledgeBases() {
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [loading, setLoading] = useState(true);
  useEffect(() => knowledgeCatalogEvents.subscribe(setKnowledgeBases), []);

  useEffect(() => {
    let active = true;
    void getKnowledgeBases().then((resp) => {
      // 将 KnowledgeBaseVO 转换为 KnowledgeBase 类型
      const converted = resp.knowledgeBases.map((kb) => ({
        knowledgeBaseId: kb.id,
        name: kb.name,
        description: kb.description || "",
        readOnly: kb.readOnly,
      }));
      if (active) setKnowledgeBases(converted);
    }).catch((error) => {
      if (active) showRequestError(error, "知识库加载失败，请稍后刷新");
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

  async function createKnowledgeBaseHandle(
    request: CreateKnowledgeBaseRequest,
  ) {
    await createKnowledgeBase(request);
    const resp = await getKnowledgeBases();
    // 将 KnowledgeBaseVO 转换为 KnowledgeBase 类型
    const converted = resp.knowledgeBases.map((kb) => ({
      knowledgeBaseId: kb.id,
      name: kb.name,
      description: kb.description || "",
      readOnly: kb.readOnly,
    }));
    knowledgeCatalogEvents.publish(converted);
  }

  return {
    knowledgeBases,
    loading,
    createKnowledgeBaseHandle,
  };
}

