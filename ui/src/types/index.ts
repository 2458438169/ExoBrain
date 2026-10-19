export type MessageType = "user" | "assistant" | "system" | "tool";

export interface KnowledgeBase {
  knowledgeBaseId: string;
  name: string;
  description: string;
  readOnly?: boolean;
  publicResource?: boolean;
}

export interface ToolCall {
  id: string;
  type: string;
  name: string;
  arguments: string;
}

export interface ToolResponse {
  id: string;
  name: string;
  responseData: string;
}

/**
 * 回答引用的来源位置（共享契约 §2 / §4）。
 * 旧版索引没有可靠位置时，sourceHash/startLine/endLine 为 null 或缺失，界面只能打开整份文档。
 */
export interface CitationSource {
  documentId: string;
  documentName: string;
  headingPath?: string[];
  sourceHash?: string | null;
  startLine?: number | null;
  endLine?: number | null;
}

/**
 * 服务端校验过的回答引用（共享契约 §4）。只有这类结构化引用会变成可点击来源；
 * 模型正文里随手写的 [1] 或 Markdown 链接不在此列。
 * citationId/retrievalId/kbId/chunkId/indexVersion/indexRevision 是内部检索字段，
 * 公开精选只暴露展示字段，因此它们可能缺失。
 */
export interface AnswerCitation {
  label: number;
  citationId?: string | null;
  retrievalId?: string | null;
  kbId?: string | null;
  chunkId?: string | null;
  source: CitationSource;
  indexVersion?: string | null;
  indexRevision?: string | null;
  excerpt: string;
}

export interface ChatMessageVOMetadata {
  toolCalls?: ToolCall[];
  toolResponse?: ToolResponse;
  citations?: AnswerCitation[];
}

export interface ChatMessageVO {
  id: string;
  sessionId: string;
  role: MessageType;
  content: string;
  metadata?: ChatMessageVOMetadata;
}

export type SseMessageType =
  | "AI_GENERATED_CONTENT"
  | "AI_PLANNING"
  | "AI_THINKING"
  | "AI_EXECUTING"
  | "AI_DONE";

export interface SseMessagePayload {
  message: ChatMessageVO;
  statusText: string;
  done: boolean;
}

export interface SseMessageMetadata {
  chatMessageId?: string;
  runId?: string;
  eventId?: string;
  sequence?: number;
}

export interface SseMessage {
  type: SseMessageType;
  payload: SseMessagePayload;
  metadata: SseMessageMetadata;
}
