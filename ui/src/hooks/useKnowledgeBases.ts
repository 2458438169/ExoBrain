import { useEffect, useState } from "react";
import {
  createKnowledgeBase,
  deleteKnowledgeBase,
  type CreateKnowledgeBaseRequest,
  getKnowledgeBases,
} from "../api/api.ts";
import type { KnowledgeBase } from "../types";
import { showRequestError } from "../api/http.ts";
import { agentCatalogEvents, knowledgeCatalogEvents } from "./catalogEvents.ts";

export function useKnowledgeBases() {
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [loading, setLoading] = useState(true);
  useEffect(() => knowledgeCatalogEvents.subscribe(setKnowledgeBases), []);

  useEffect(() => {
    let active = true;
    const revision = knowledgeCatalogEvents.revision;
    void getKnowledgeBases().then((resp) => {
      // 将 KnowledgeBaseVO 转换为 KnowledgeBase 类型
      const converted = resp.knowledgeBases.map((kb) => ({
        knowledgeBaseId: kb.id,
        name: kb.name,
        description: kb.description || "",
        readOnly: kb.readOnly,
        publicResource: kb.publicResource,
      }));
      if (active && revision === knowledgeCatalogEvents.revision) setKnowledgeBases(converted);
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
      publicResource: kb.publicResource,
    }));
    knowledgeCatalogEvents.publish(converted);
  }

  async function deleteKnowledgeBaseHandle(knowledgeBaseId: string) {
    await deleteKnowledgeBase(knowledgeBaseId);
    knowledgeCatalogEvents.publish(knowledgeBases.filter((kb) => kb.knowledgeBaseId !== knowledgeBaseId));
    agentCatalogEvents.publish((items) => items.map((agent) => ({
      ...agent, allowedKbs: agent.allowedKbs?.filter((id) => id.toLowerCase() !== knowledgeBaseId.toLowerCase()),
    })));
  }

  return {
    knowledgeBases,
    loading,
    createKnowledgeBaseHandle,
    deleteKnowledgeBaseHandle,
  };
}

