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
  type CreateChatMessageResponse,
} from "../../api/api.ts";
import { useAgents } from "../../hooks/useAgents.ts";
import EmptyAgentChatView from "./agentChatView/EmptyAgentChatView.tsx";
import type { ChatMessageVO, SseMessage, SseMessageType } from "../../types";
import { showRequestError, SSE_BASE_URL } from "../../api/http.ts";
import { FOLLOW_UP_PROMPTS, IS_PUBLIC_DEMO } from "../../config.ts";
import SessionAgentLabel, { type SessionAgentCatalog } from "../SessionAgentLabel.tsx";
import { cancelRun, getRun, listSessionRuns } from "../../api/execution.ts";

interface InitialMessage {
  agentId: string;
  content: string;
}

function readInitialMessage(state: unknown): InitialMessage | null {
  const candidate = (state as { initialMessage?: InitialMessage } | null)?.initialMessage;
  return candidate && typeof candidate.agentId === "string" && typeof candidate.content === "string"
    ? candidate : null;
}

// 07 / F02：一次「发送意图」对应一个稳定的幂等键。网络异常后重试、SSE 重连后的首条消息恢复
// 都复用它；服务端受理成功后该意图结束，下一次发送才生成新键。
// 不按问题文本去重：用户有意重复提问时应当产生新的运行。
interface SendIntent {
  key: string;
  sessionId: string;
  agentId: string;
  content: string;
}

function newIdempotencyKey(): string {
  const cryptoObject = globalThis.crypto;
  if (cryptoObject && typeof cryptoObject.randomUUID === "function") {
    return cryptoObject.randomUUID();
  }
  return `intent-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function sameIntent(intent: SendIntent | null, sessionId: string, agentId: string, content: string): boolean {
  return !!intent && intent.sessionId === sessionId && intent.agentId === agentId && intent.content === content;
}

// R2-02：提交响应里的运行状态必须被消费。终态重放不会再发 AI_DONE，只靠 SSE 收尾会让界面永久停在
// “生成中”并锁住输入框。
const TERMINAL_RUN_STATUSES = new Set(["SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED"]);

function isTerminalRunStatus(runStatus: string | undefined): boolean {
  return !!runStatus && TERMINAL_RUN_STATUSES.has(runStatus);
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
  // 已知 runId 才能调用真实取消接口；取消请求登记 ≠ 终态。
  const [activeRunId, setActiveRunId] = useState<string | null>(null);
  const [cancelRequested, setCancelRequested] = useState(false);
  const waitingRef = useRef(false);

  useEffect(() => {
    waitingRef.current = waiting;
  }, [waiting]);

  const refreshMessages = useCallback(async () => {
    const response = await getChatMessagesBySessionId(sessionId);
    setMessages((current) => mergeSnapshot(current, response.chatMessages));
  }, [sessionId]);

  const pendingIntent = useRef<SendIntent | null>(null);
  const inFlightIntent = useRef<{ intent: SendIntent; promise: Promise<CreateChatMessageResponse> } | null>(null);

  // R2-02：把提交响应里的运行状态落实成界面状态。终态重放不会再发 AI_DONE，
  // 因此必须在这里恢复输入框并给出完成提示；仍在排队/执行则保持等待，由 SSE 与运行轮询收尾。
  const settleSubmission = useCallback((response: CreateChatMessageResponse) => {
    if (!isTerminalRunStatus(response?.runStatus)) return;
    setWaiting(false);
    setStatus(null);
    setActiveRunId(null);
    setCancelRequested(false);
    if (response.idempotentReplay) {
      setCompletionNotice(response.runStatus === "SUCCEEDED"
        ? "该问题此前已提交并完成，这次没有重复执行"
        : "该问题此前已结束，没有新的答复");
    } else if (response.runStatus !== "SUCCEEDED") {
      setCompletionNotice("本次运行已结束，没有产出答复");
    }
  }, []);

  /**
   * 唯一的发送协调器：首页推荐、示例页「亲自试一试」、手动发送、相关追问都只会走到这里。
   *
   * - 同一个发送意图复用同一个幂等键（pendingIntent），请求失败只保留意图，不换键重发；
   * - 服务端确认受理（含 idempotentReplay）后统一收尾：清掉首条自动发送标记、记录 runId；
   * - 终态重放立即结束等待，不依赖一个不会再来的 AI_DONE。
   */
  const sendIntent = useCallback(async (agentId: string, content: string) => {
    const trimmed = content.trim();
    const reused = pendingIntent.current;
    const intent: SendIntent = reused && sameIntent(reused, sessionId, agentId, trimmed)
      ? reused
      : { key: newIdempotencyKey(), sessionId, agentId, content: trimmed };
    // 同一个意图在提交过程中被重复触发（快双击、连按 Enter）：复用同一次请求，
    // 不并行提交，也不会产生第二个用户问题。
    if (inFlightIntent.current && sameIntent(inFlightIntent.current.intent, sessionId, agentId, trimmed)) {
      return inFlightIntent.current.promise;
    }
    pendingIntent.current = intent;
    const promise = createChatMessage({
      agentId,
      sessionId,
      role: "user",
      content: trimmed,
      idempotencyKey: intent.key,
    }).then((response) => {
      pendingIntent.current = null;
      if (initialMessage.current) {
        // 任何一次「确认受理」都意味着本会话已经有了一条被接受的用户消息：
        // 首条消息的自动恢复到此结束，SSE 重连的 init 不许再用新键重发一遍。
        initialMessage.current = null;
        initialSent.current = true;
      }
      setActiveRunId(response.runId ?? null);
      setCancelRequested(false);
      settleSubmission(response);
      return response;
    }).finally(() => {
      if (inFlightIntent.current?.intent === intent) inFlightIntent.current = null;
    });
    inFlightIntent.current = { intent, promise };
    return promise;
  }, [sessionId, settleSubmission]);

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

  // 刷新/重连后的状态恢复：已经受理的运行从服务器恢复，不自动重发。
  // 只读取运行摘要，用它把「活动状态 + 可取消的 runId」还原出来。
  useEffect(() => {
    let active = true;
    void listSessionRuns(sessionId).then((response) => {
      if (!active) return;
      const running = (response?.runs ?? []).find(
        (run) => run.status === "QUEUED" || run.status === "RUNNING",
      );
      if (!running) return;
      setWaiting(true);
      setActiveRunId(running.runId);
      setStatus({
        type: "AI_EXECUTING",
        text: running.status === "QUEUED" ? "任务已受理，正在等待处理" : "正在执行；刷新页面不会中断它",
      });
    }).catch(() => {
      // 读取失败时不虚报运行状态：历史区会显示读取失败与重试。
    });
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
        // P02：没有正在等待的本轮运行时，迟到的旧事件不能把界面重新变成「生成中」。
        if (!waitingRef.current) return;
        setWaiting(true);
        setStatus({ type: update.type, text: update.payload.statusText });
      } else if (update.type === "AI_DONE") {
        setWaiting(false);
        setStatus(null);
        setActiveRunId(null);
        setCancelRequested(false);
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
      void sendIntent(first.agentId, first.content).then(async () => {
        if (!active) return;
        setDraft((current) => (current === first.content ? "" : current));
        await refreshMessages();
      }).catch((error) => {
        if (!active) return;
        // 首条消息没有确认受理：允许下一次 init（重连）用同一个幂等键重试，不会产生第二个运行。
        // 注意这里只回退“已发送”标记，不清空 initialMessage：重连还要靠它拿到同一份内容与同一个键。
        initialSent.current = false;
        setWaiting(false);
        setStatus(null);
        showRequestError(error, "发送消息失败，请重试");
      });
    });

    return () => {
      active = false;
      es.close();
    };
  }, [sessionId, navigate, refreshMessages, sendIntent]);

  /**
   * 停止按钮：只有拿到 runId 才能调用真实取消接口。
   * cancelRequested=true 只表示停止请求已经登记，界面继续显示活动状态直到运行进入终态。
   */
  const handleCancel = useCallback(async () => {
    if (!activeRunId) {
      setCompletionNotice("问题正在提交，还没有可取消的运行。");
      return;
    }
    if (cancelRequested) return;
    try {
      const result = await cancelRun(activeRunId);
      setCompletionNotice(result.message);
      if (isTerminalRunStatus(result.status)) {
        // 运行已经在终态：以服务端状态收尾，不显示「正在取消」。
        setWaiting(false);
        setStatus(null);
        setActiveRunId(null);
        setCancelRequested(false);
        setCompletionNotice(result.message || "运行已经结束。");
        return;
      }
      setCancelRequested(true);
      setStatus({ type: "AI_EXECUTING", text: "已登记停止请求，等待运行状态确认" });
    } catch (error) {
      setCancelRequested(false);
      showRequestError(error, "取消请求失败，运行仍在继续");
    }
  }, [activeRunId, cancelRequested]);

  // 取消请求登记后按真实运行状态收敛；不停留在「正在取消」。
  useEffect(() => {
    if (!cancelRequested || !activeRunId) return;
    let active = true;
    const timer = window.setInterval(() => {
      void getRun(activeRunId).then((detail) => {
        if (!active || !detail?.summary) return;
        if (isTerminalRunStatus(detail.summary.status)) {
          setWaiting(false);
          setStatus(null);
          setActiveRunId(null);
          setCancelRequested(false);
          setCompletionNotice(detail.summary.status === "CANCELLED"
            ? "本次运行已取消。"
            : "本次运行已结束。");
        }
      }).catch(() => {
        // 读取失败不会假装已经停止；继续按原状态显示。
      });
    }, 3000);
    return () => { active = false; window.clearInterval(timer); };
  }, [cancelRequested, activeRunId]);

  /** 手动发送与相关追问共用同一条路径；草稿只在确认受理后清空。 */
  const handleSendMessage = async (content: string): Promise<boolean> => {
    if (!content.trim() || !session?.agentId || !connected || waiting) return false;
    setWaiting(true);
    setCancelRequested(false);
    setStatus(null);
    setActiveRunId(null);
    setCompletionNotice("");
    try {
      await sendIntent(session.agentId, content);
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
    <div className="flex h-full min-h-0 min-w-0 flex-col">
      <div className="shrink-0 border-b-2 border-dashed border-sketch bg-white/70 px-4 py-2 backdrop-blur-sm sm:px-8 sm:py-3">
        <div className="flex flex-wrap items-center gap-2">
          <span className="min-w-0 flex-1 truncate font-semibold text-ink">{session.title || "与 ExoBrain 对话"}</span>
          {IS_PUBLIC_DEMO && <Tag color="magenta" className="m-0">我的体验记录</Tag>}
          <span className={connected ? "shrink-0 rounded-full bg-teal-50 px-2 py-0.5 text-xs font-semibold text-teal-600" : "shrink-0 rounded-full bg-amber-50 px-2 py-0.5 text-xs font-semibold text-amber-600"}>
            {connected ? "已连接" : connectionFailed ? "连接已断开，正在重试…" : "正在连接…"}
          </span>
        </div>
        <SessionAgentLabel agentId={session.agentId} catalog={agentCatalog} className="mt-1 text-xs" />
      </div>
      <AgentChatHistory
        messages={messages}
        sessionId={sessionId}
        displayAgentStatus={waiting}
        agentStatusText={status?.text || "正在生成回答…"}
        agentStatusType={status?.type}
        cancelling={cancelRequested}
        busy={waiting}
        onAskFollowUp={IS_PUBLIC_DEMO ? handleSendMessage : undefined}
        followUpPrompts={IS_PUBLIC_DEMO ? FOLLOW_UP_PROMPTS : []}
      />
      <div
        className="shrink-0 border-t-2 border-dashed border-sketch bg-white/70 backdrop-blur-sm"
        style={{ paddingBottom: "max(0.75rem, env(safe-area-inset-bottom))" }}
      >
        <div className="mx-auto w-full max-w-3xl px-4 pt-3 sm:px-8">
          {completionNotice && <p role="status" className="mb-1.5 break-words text-xs text-slate-500">{completionNotice}</p>}
          {!session.agentId && <p className="mb-1.5 text-xs text-slate-500">此会话的 Agent 绑定未记录或已移除。历史仍可查看，请新建对话继续提问。</p>}
          <AgentChatInput
            value={draft}
            onChange={setDraft}
            onSend={handleSendMessage}
            waiting={waiting}
            onCancel={() => void handleCancel()}
            cancellable={!!activeRunId}
            cancelling={cancelRequested}
            connected={connected}
            disabled={!session.agentId}
          />
          {IS_PUBLIC_DEMO && <p className="mt-1.5 text-center text-xs text-slate-400">历史仅对当前浏览器可见；回答会标注来源，执行过程可展开查看。</p>}
        </div>
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
    : (
      <EmptyAgentChatView
        key={location.key}
        agents={agentCatalog.agents}
        loadingAgents={agentCatalog.loading}
        agentsFailed={agentCatalog.loadFailed}
        onRetryAgents={() => void agentCatalog.refreshAgents().catch((error) => showRequestError(error, "助手加载失败"))}
      />
    );
}
