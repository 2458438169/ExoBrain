import { get, post, patch, del, request } from "./http.ts";
import type { ChatMessageVO, MessageType } from "../types";

// 类型定义
export interface ChatOptions {
  temperature?: number;
  topP?: number;
  messageLength?: number;
}

export type ModelType = string;

export interface CreateAgentRequest {
  name: string;
  description?: string;
  systemPrompt?: string;
  model: ModelType;
  allowedTools?: string[];
  allowedKbs?: string[];
  chatOptions?: ChatOptions;
}

export interface UpdateAgentRequest {
  name?: string;
  description?: string;
  systemPrompt?: string;
  model?: ModelType;
  allowedTools?: string[];
  allowedKbs?: string[];
  chatOptions?: ChatOptions;
}

export interface CreateAgentResponse {
  agentId: string;
}

export interface AgentVO {
  id: string;
  name: string;
  description?: string;
  systemPrompt?: string;
  model: ModelType;
  allowedTools?: string[];
  allowedKbs?: string[];
  chatOptions?: ChatOptions;
  createdAt?: string;
  updatedAt?: string;
  readOnly?: boolean;
}

export interface GetAgentsResponse {
  agents: AgentVO[];
}

/**
 * 获取所有 agents
 */
export async function getAgents(): Promise<GetAgentsResponse> {
  return get<GetAgentsResponse>("/agents");
}

/**
 * 创建 agent
 */
export async function createAgent(
  request: CreateAgentRequest,
): Promise<CreateAgentResponse> {
  return post<CreateAgentResponse>("/agents", request);
}

/**
 * 删除 agent
 */
export async function deleteAgent(agentId: string): Promise<void> {
  return del<void>(`/agents/${agentId}`);
}

/**
 * 更新 agent
 */
export async function updateAgent(
  agentId: string,
  request: UpdateAgentRequest,
): Promise<void> {
  return patch<void>(`/agents/${agentId}`, request);
}

/**
 * 创建聊天会话
 */
export interface CreateChatSessionRequest {
  agentId: string;
  title?: string;
}

export interface CreateChatSessionResponse {
  chatSessionId: string;
}

export async function createChatSession(
  request: CreateChatSessionRequest,
): Promise<CreateChatSessionResponse> {
  return post<CreateChatSessionResponse>("/chat-sessions", request);
}

/**
 * 聊天会话相关类型和接口
 */
export interface ChatSessionVO {
  id: string;
  agentId: string | null;
  title?: string;
}

export interface GetChatSessionsResponse {
  chatSessions: ChatSessionVO[];
}

export interface GetChatSessionResponse {
  chatSession: ChatSessionVO;
}

export interface UpdateChatSessionRequest {
  title?: string;
}

/**
 * 获取所有聊天会话
 */
export async function getChatSessions(): Promise<GetChatSessionsResponse> {
  return get<GetChatSessionsResponse>("/chat-sessions");
}

/**
 * 获取单个聊天会话
 */
export async function getChatSession(
  chatSessionId: string,
): Promise<GetChatSessionResponse> {
  return get<GetChatSessionResponse>(`/chat-sessions/${chatSessionId}`);
}

/**
 * 根据 agentId 获取聊天会话
 */
export async function getChatSessionsByAgentId(
  agentId: string,
): Promise<GetChatSessionsResponse> {
  return get<GetChatSessionsResponse>(`/chat-sessions/agent/${agentId}`);
}

/**
 * 更新聊天会话
 */
export async function updateChatSession(
  chatSessionId: string,
  request: UpdateChatSessionRequest,
): Promise<void> {
  return patch<void>(`/chat-sessions/${chatSessionId}`, request);
}

/**
 * 删除聊天会话
 */
export async function deleteChatSession(chatSessionId: string): Promise<void> {
  return del<void>(`/chat-sessions/${chatSessionId}`);
}

/**
 * 聊天消息相关类型和接口
 */
export interface MetaData {
  [key: string]: unknown;
}

export interface GetChatMessagesResponse {
  chatMessages: ChatMessageVO[];
}

export interface CreateChatMessageRequest {
  agentId: string;
  sessionId: string;
  role: MessageType;
  content: string;
  metadata?: MetaData;
  /**
   * 07 / F02：一次「发送意图」的稳定幂等键。网络重试必须复用同一个值；
   * 服务端以 (actor, session, key) 去重，返回原来的 message/run 并标记 idempotentReplay。
   */
  idempotencyKey?: string;
}

export interface CreateChatMessageResponse {
  chatMessageId: string;
  /** 07：本次提交对应的运行；幂等重放时与原提交相同。 */
  runId?: string;
  /** QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED / INTERRUPTED */
  runStatus?: string;
  /** 本次响应是否为幂等重放（没有再次执行、没有再次调用模型）。 */
  idempotentReplay?: boolean;
}

export interface UpdateChatMessageRequest {
  content?: string;
  metadata?: MetaData;
}

/**
 * 根据 sessionId 获取聊天消息
 */
export async function getChatMessagesBySessionId(
  sessionId: string,
): Promise<GetChatMessagesResponse> {
  return get<GetChatMessagesResponse>(`/chat-messages/session/${sessionId}`);
}

/**
 * 创建聊天消息
 */
export async function createChatMessage(
  request: CreateChatMessageRequest,
): Promise<CreateChatMessageResponse> {
  return post<CreateChatMessageResponse>("/chat-messages", request);
}

/**
 * 更新聊天消息
 */
export async function updateChatMessage(
  chatMessageId: string,
  request: UpdateChatMessageRequest,
): Promise<void> {
  return patch<void>(`/chat-messages/${chatMessageId}`, request);
}

/**
 * 删除聊天消息
 */
export async function deleteChatMessage(chatMessageId: string): Promise<void> {
  return del<void>(`/chat-messages/${chatMessageId}`);
}

/**
 * 知识库相关类型和接口
 */
export interface KnowledgeBaseVO {
  id: string;
  name: string;
  description?: string;
  readOnly?: boolean;
  publicResource?: boolean;
}

export interface CreateKnowledgeBaseRequest {
  name: string;
  description?: string;
}

export interface UpdateKnowledgeBaseRequest {
  name?: string;
  description?: string;
}

export interface GetKnowledgeBasesResponse {
  knowledgeBases: KnowledgeBaseVO[];
}

export interface CreateKnowledgeBaseResponse {
  knowledgeBaseId: string;
}

/**
 * 获取所有知识库
 */
export async function getKnowledgeBases(): Promise<GetKnowledgeBasesResponse> {
  return get<GetKnowledgeBasesResponse>("/knowledge-bases");
}

/**
 * 创建知识库
 */
export async function createKnowledgeBase(
  request: CreateKnowledgeBaseRequest,
): Promise<CreateKnowledgeBaseResponse> {
  return post<CreateKnowledgeBaseResponse>("/knowledge-bases", request);
}

/**
 * 删除知识库
 */
export async function deleteKnowledgeBase(
  knowledgeBaseId: string,
): Promise<void> {
  return del<void>(`/knowledge-bases/${knowledgeBaseId}`);
}

/**
 * 更新知识库
 */
export async function updateKnowledgeBase(
  knowledgeBaseId: string,
  request: UpdateKnowledgeBaseRequest,
): Promise<void> {
  return patch<void>(`/knowledge-bases/${knowledgeBaseId}`, request);
}

/**
 * 文档相关类型和接口
 */
export interface DocumentVO {
  id: string;
  kbId: string;
  filename: string;
  filetype: string;
  size: number;
  readOnly?: boolean;
}

export interface GetDocumentsResponse {
  documents: DocumentVO[];
}

export interface DocumentContentResponse {
  documentId: string;
  filename: string;
  content: string;
}

export interface CreateDocumentResponse {
  documentId: string;
}

/**
 * 根据知识库 ID 获取文档列表
 */
export async function getDocumentsByKbId(
  kbId: string,
): Promise<GetDocumentsResponse> {
  return get<GetDocumentsResponse>(`/documents/kb/${kbId}`);
}

export async function getDocumentContent(
  documentId: string,
  signal?: AbortSignal,
): Promise<DocumentContentResponse> {
  return get<DocumentContentResponse>(
    `/documents/${encodeURIComponent(documentId)}/content`,
    undefined,
    { signal },
  );
}

/**
 * 文档原文接口的失败分类。HTTP 客户端只向上抛服务端的安全文案（不暴露状态码与路径），
 * 这里把已知文案映射成可区分的反馈：来源不可用 / 无权访问 / 暂时失败。
 * 未知文案一律按“暂时失败”处理，绝不回退到文件路径或隐藏文件名。
 */
export type DocumentContentFailure = "unavailable" | "forbidden" | "temporary";

export function describeDocumentContentFailure(
  error: unknown,
): { kind: DocumentContentFailure; detail: string } {
  const message = error instanceof Error && error.message ? error.message : "";
  if (message.includes("不存在") || message.includes("不可用")) {
    return { kind: "unavailable", detail: "这份文档已被删除，或当前账号已无权访问它。" };
  }
  if (message.includes("权限") || message.includes("登录")) {
    return { kind: "forbidden", detail: "当前账号无权访问这份来源，请确认登录状态。" };
  }
  return { kind: "temporary", detail: message || "暂时无法加载这份文档，请稍后重试。" };
}

/**
 * 上传文档
 */
export async function uploadDocument(
  kbId: string,
  file: File,
): Promise<CreateDocumentResponse> {
  const formData = new FormData();
  formData.append("kbId", kbId);
  formData.append("file", file);

  return request<CreateDocumentResponse>("/documents/upload", {
    method: "POST",
    body: formData,
  });
}

/**
 * 删除文档
 */
export async function deleteDocument(documentId: string): Promise<void> {
  return del<void>(`/documents/${documentId}`);
}

/**
 * 工具相关类型和接口
 */
export type ToolType = "FIXED" | "OPTIONAL";

export interface ToolVO {
  name: string;
  description: string;
  type: ToolType;
}

export interface GetOptionalToolsResponse {
  tools: ToolVO[];
}

/**
 * 获取可选工具列表
 */
export async function getOptionalTools(): Promise<GetOptionalToolsResponse> {
  const tools = await get<ToolVO[]>("/tools");
  return { tools };
}
