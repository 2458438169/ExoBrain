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
