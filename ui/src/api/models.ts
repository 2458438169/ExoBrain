import { del, get, patch, post } from "./http.ts";

export type ModelKind = "LLM" | "EMBEDDING";
export type ModelProtocol = "openai" | "deepseek" | "zhipuai" | "ollama";

export interface AvailableModel {
  id: string;
  name: string;
  kind: ModelKind;
  model: string;
  enabled: boolean;
  defaultModel: boolean;
  dimensions?: number;
}

export interface AdminModel extends AvailableModel {
  protocol: ModelProtocol;
  baseUrl: string;
  apiKeyConfigured: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface ModelRequest {
  id: string;
  name: string;
  kind: ModelKind;
  protocol: ModelProtocol;
  baseUrl: string;
  model: string;
  apiKey?: string;
  clearApiKey?: boolean;
  enabled: boolean;
  defaultModel: boolean;
  dimensions?: number;
}

/** 一个知识库当前绑定的向量模型（管理页「迁移知识库」）。 */
export interface KnowledgeBaseBinding {
  knowledgeBaseId: string;
  name: string;
  modelId: string;
}

export interface RebindResult {
  modelId: string;
  rebound: number;
  knowledgeBaseIds: string[];
}

export interface ModelTestResult {
  success: boolean;
  message: string;
  latencyMs?: number;
  dimensions?: number;
}

export const getAvailableModels = () => get<{ models: AvailableModel[] }>("/models", undefined, { silent: true });
export const getAdminModels = () => get<{ models: AdminModel[] }>("/admin/models", undefined, { silent: true });
export const createAdminModel = (body: ModelRequest) =>
  post<{ model: AdminModel }>("/admin/models", body, { silent: true });
export const updateAdminModel = (id: string, body: Partial<Omit<ModelRequest, "id">>) =>
  patch<{ model: AdminModel }>(`/admin/models/${encodeURIComponent(id)}`, body, { silent: true });
export const deleteAdminModel = (id: string) =>
  del<void>(`/admin/models/${encodeURIComponent(id)}`, undefined, { silent: true });
export const testAdminModel = (id: string) =>
  post<ModelTestResult>(`/admin/models/${encodeURIComponent(id)}/test`, undefined, { silent: true });

export const getModelBindings = () =>
  get<{ bindings: KnowledgeBaseBinding[] }>("/admin/model-bindings", undefined, { silent: true });

/** 把已有知识库迁移到该向量模型；vectorSpaceUnchanged 必须在确认同一向量空间后显式为 true。 */
export const rebindKnowledgeBases = (
  id: string,
  body: { vectorSpaceUnchanged: boolean; knowledgeBaseIds?: string[] },
) => post<RebindResult>(`/admin/models/${encodeURIComponent(id)}/knowledge-bases`, body, { silent: true });
