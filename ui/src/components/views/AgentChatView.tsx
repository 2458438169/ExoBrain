import { useCallback, useEffect, useRef, useState } from "react";
import { Button, Empty, Spin, Tag } from "antd";
import { useParams, useNavigate, useLocation } from "react-router-dom";
import AgentChatHistory from "./agentChatView/AgentChatHistory.tsx";
import AgentChatInput from "./agentChatView/AgentChatInput.tsx";
import {
  createChatMessage,
  getChatMessagesBySessionId,
  getChatSession,
  type ChatSessionVO,
} from "../../api/api.ts";
import { useAgents } from "../../hooks/useAgents.ts";
import EmptyAgentChatView from "./agentChatView/EmptyAgentChatView.tsx";
import type { ChatMessageVO, SseMessage, SseMessageType } from "../../types";
import { showRequestError, SSE_BASE_URL } from "../../api/http.ts";
import { IS_PUBLIC_DEMO } from "../../config.ts";
import SessionAgentLabel, { type SessionAgentCatalog } from "../SessionAgentLabel.tsx";

interface InitialMessage {
  agentId: string;
  content: string;
}

function readInitialMessage(state: unknown): InitialMessage | null {
  const candidate = (state as { initialMessage?: InitialMessage } | null)?.initialMessage;
  return candidate && typeof candidate.agentId === "string" && typeof candidate.content === "string"
    ? candidate : null;
}

// A history refresh can race an SSE event; keep new events without duplicating persisted messages.
function mergeSnapshot(current: ChatMessageVO[], snapshot: ChatMessageVO[]): ChatMessageVO[] {
  const ids = new Set(snapshot.map((message) => message.id));
  return [...snapshot, ...current.filter((message) => !ids.has(message.id))];
}

function SessionChatView({ sessionId, agentCatalog }: { sessionId: string; agentCatalog: SessionAgentCatalog }) {
  const navigate = useNavigate();
  const { state } = useLocation();
  const initialMessage = useRef(readInitialMessage(state));
  const initialSent = useRef(false);
  const [draft, setDraft] = useState(() => readInitialMessage(state)?.content ?? "");
  const [session, setSession] = useState<ChatSessionVO | null>(null);
  const [messages, setMessages] = useState<ChatMessageVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [connected, setConnected] = useState(false);
  const [connectionFailed, setConnectionFailed] = useState(false);
  const [waiting, setWaiting] = useState(false);
  const [status, setStatus] = useState<{ type: SseMessageType; text: string } | null>(null);
  const [completionNotice, setCompletionNotice] = useState("");

  const refreshMessages = useCallback(async () => {
    const response = await getChatMessagesBySessionId(sessionId);
    setMessages((current) => mergeSnapshot(current, response.chatMessages));
  }, [sessionId]);

  useEffect(() => {
    let active = true;
    void Promise.all([
      getChatMessagesBySessionId(sessionId),
      getChatSession(sessionId),
    ]).then(([messageResponse, sessionResponse]) => {
      if (!active) return;
      setMessages((current) => mergeSnapshot(current, messageResponse.chatMessages));
      setSession(sessionResponse.chatSession);
    }).catch((error) => {
      if (active) showRequestError(error, "加载聊天会话失败，请重试");
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [sessionId]);

  useEffect(() => {
    let active = true;
    const es = new EventSource(`${SSE_BASE_URL}/connect/${encodeURIComponent(sessionId)}`);
    es.onerror = () => {
      if (!active) return;
      setConnected(false);
      setConnectionFailed(true);
      setWaiting(false);
      setStatus(null);
    };

    es.addEventListener("message", (event) => {
      if (!active) return;
      let update: SseMessage;
      try {
        update = JSON.parse(event.data) as SseMessage;
      } catch {
        return;
      }
      if (!update.payload) return;
      if (update.type === "AI_GENERATED_CONTENT") {
        const incoming = update.payload.message;
        if (!incoming || incoming.sessionId !== sessionId) return;
        setMessages((current) => current.some((message) => message.id === incoming.id)
          ? current.map((message) => message.id === incoming.id ? incoming : message)
          : [...current, incoming]);
      } else if (["AI_PLANNING", "AI_THINKING", "AI_EXECUTING"].includes(update.type)) {
        setWaiting(true);
        setStatus({ type: update.type, text: update.payload.statusText });
      } else if (update.type === "AI_DONE") {
        setWaiting(false);
        setStatus(null);
        setCompletionNotice(update.payload.statusText || "");
        void refreshMessages().catch((error) => showRequestError(error, "聊天记录刷新失败"));
      }
    });

    es.addEventListener("init", () => {
      if (!active) return;
      setConnected(true);
      setConnectionFailed(false);
      // Reconcile history after reconnection, including any messages received while offline.
      void refreshMessages().catch((error) => showRequestError(error, "聊天记录刷新失败"));
      const first = initialMessage.current;
      if (!first || initialSent.current) return;
      initialSent.current = true;
      setWaiting(true);
      navigate(`/chat/${sessionId}`, { replace: true, state: null });
      void createChatMessage({
        agentId: first.agentId,
        sessionId,
        role: "user",
        content: first.content,
      }).then(async () => {
        if (!active) return;
        setDraft("");
        await refreshMessages();
      }).catch((error) => {
        if (!active) return;
        setWaiting(false);
        setStatus(null);
        showRequestError(error, "发送消息失败，请重试");
      });
    });

    return () => {
      active = false;
      es.close();
    };
  }, [sessionId, navigate, refreshMessages]);

  const handleSendMessage = async (content: string): Promise<boolean> => {
    if (!content.trim() || !session?.agentId || !connected || waiting) return false;
    setWaiting(true);
    setCompletionNotice("");
    try {
      await createChatMessage({
        agentId: session.agentId,
        sessionId,
        role: "user",
        content: content.trim(),
      });
      void refreshMessages().catch((error) => showRequestError(error, "聊天记录刷新失败"));
      return true;
    } catch (error) {
      setWaiting(false);
      setStatus(null);
      showRequestError(error, "发送消息失败，请重试");
      return false;
    }
  };

  if (loading) return <div className="flex h-full items-center justify-center"><Spin /></div>;
  if (!session) {
    return (
      <div className="flex h-full items-center justify-center p-6">
        <Empty description="会话不存在或无法访问">
          <Button onClick={() => navigate("/chat")}>开始新对话</Button>
        </Empty>
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col bg-white">
      <div className="shrink-0 border-b border-slate-100 px-5 py-4 sm:px-8">
        <div className="flex flex-wrap items-center gap-2">
          <span className="min-w-0 flex-1 truncate font-medium text-slate-900">{session.title || "与 ExoBrain 对话"}</span>
          {IS_PUBLIC_DEMO && <Tag color="geekblue">我的体验记录</Tag>}
          <span className={connected ? "text-xs text-teal-600" : "text-xs text-amber-600"}>
            {connected ? "已连接" : connectionFailed ? "连接已断开，正在重试…" : "正在连接…"}
          </span>
        </div>
        <SessionAgentLabel agentId={session.agentId} catalog={agentCatalog} className="mt-2 text-sm" />
      </div>
      <AgentChatHistory
        messages={messages}
        sessionId={sessionId}
        displayAgentStatus={waiting}
        agentStatusText={status?.text || "正在生成回答…"}
        agentStatusType={status?.type}
      />
      <div className="shrink-0 border-t border-slate-100 bg-white p-4 sm:px-8">
        {completionNotice && <p role="status" className="mb-2 text-xs text-slate-500">{completionNotice}</p>}
        {!session.agentId && <p className="mb-2 text-xs text-slate-500">此会话的 Agent 绑定未记录或已移除。历史仍可查看，请新建对话继续提问。</p>}
        <AgentChatInput
          value={draft}
          onChange={setDraft}
          onSend={handleSendMessage}
          disabled={!session.agentId || !connected || waiting}
          loading={waiting}
        />
        {IS_PUBLIC_DEMO && <p className="mt-2 text-xs text-slate-400">历史仅对当前浏览器可见，工具调用与结果可展开查看。</p>}
      </div>
    </div>
  );
}

export default function AgentChatView() {
  const { chatSessionId } = useParams<{ chatSessionId: string }>();
  const location = useLocation();
  const agentCatalog = useAgents();
  return chatSessionId
    ? <SessionChatView key={chatSessionId} sessionId={chatSessionId} agentCatalog={agentCatalog} />
    : <EmptyAgentChatView key={location.key} agents={agentCatalog.agents} loadingAgents={agentCatalog.loading} />;
}
