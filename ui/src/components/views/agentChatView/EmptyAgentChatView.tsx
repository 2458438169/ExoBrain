import { lazy, Suspense, useCallback, useMemo, useRef, useState } from "react";
import { Alert, Button, Tag, Typography } from "antd";
import { ArrowRightOutlined, BookOutlined, HistoryOutlined, ReloadOutlined } from "@ant-design/icons";
import NativeComposer from "./NativeComposer.tsx";
import { useLocation, useNavigate } from "react-router-dom";
import { type AgentVO, createChatSession } from "../../../api/api.ts";
import { getAgentEmoji } from "../../../utils";
import { useChatSessions } from "../../../hooks/useChatSessions.ts";
import { DEMO_PROMPTS, IS_PUBLIC_DEMO } from "../../../config.ts";
import { showRequestError } from "../../../api/http.ts";
import ExoBrainLogo from "../../ExoBrainLogo.tsx";

// 助手选择器只在账号构建里出现，单独成 chunk，游客首屏不必下载 rc-select。
const AgentPicker = lazy(() => import("./AgentPicker.tsx"));

const { Title, Text } = Typography;

interface EmptyAgentChatViewProps {
  agents: AgentVO[];
  loadingAgents?: boolean;
  /** 助手目录读取失败时给出可理解的状态与重试入口，而不是一个空下拉框。 */
  agentsFailed?: boolean;
  onRetryAgents?: () => void;
}

/**
 * 首页（UX-03）。
 *
 * - 一个明显的主行动：第一个推荐问题；另外两个推荐问题用紧凑入口，不占三张大卡片。
 * - 点击问题直接创建发送意图并发送：这里只创建会话并把首条问题交给聊天页的统一发送逻辑，
 *   不在这里另写一套「快捷发送」。
 * - 首次无历史时弱化侧栏的空记录区域（见 ChatTabContent），主区只讲能做什么。
 */
export default function EmptyAgentChatView({
  agents,
  loadingAgents,
  agentsFailed,
  onRetryAgents,
}: EmptyAgentChatViewProps) {
  const { state } = useLocation();
  const [message, setMessage] = useState(() =>
    typeof state?.suggestedPrompt === "string" ? state.suggestedPrompt : "",
  );
  const [pendingPrompt, setPendingPrompt] = useState<string | null>(null);
  const [selectedAgentId, setSelectedAgentId] = useState<string | null>(
    typeof state?.selectedAgentId === "string" ? state.selectedAgentId : null,
  );
  const navigate = useNavigate();
  const { refreshChatSessions } = useChatSessions();

  const effectiveAgentId = useMemo(() => {
    return agents.find((agent) => agent.id === selectedAgentId)?.id ?? agents[0]?.id ?? null;
  }, [selectedAgentId, agents]);

  const agentReady = !!effectiveAgentId;
  const busy = pendingPrompt !== null;
  const startingRef = useRef(false);

  /**
   * 首页唯一的发送入口：创建会话 → 把问题作为首条发送意图交给聊天页。
   * 聊天页在 SSE 连上后用同一个协调器提交，生成稳定幂等键；失败只回退发送标记。
   * 双击同一个问题不会开始第二个意图：正在创建会话时按钮整体禁用。
   */
  const startQuestion = useCallback(async (content: string) => {
    const trimmed = content.trim();
    // 同步的重入标记：快双击时 state 还没刷新，用 ref 保证只创建一次会话与一个发送意图。
    if (!trimmed || !effectiveAgentId || startingRef.current) return;
    startingRef.current = true;
    setPendingPrompt(trimmed);
    try {
      const response = await createChatSession({
        agentId: effectiveAgentId,
        title: trimmed.slice(0, 40),
      });
      navigate(`/chat/${response.chatSessionId}`, {
        state: { initialMessage: { content: trimmed, agentId: effectiveAgentId } },
      });
      void refreshChatSessions().catch((error) => showRequestError(error, "聊天记录刷新失败"));
    } catch (error) {
      showRequestError(error, "创建聊天会话失败，请重试");
    } finally {
      startingRef.current = false;
      setPendingPrompt(null);
    }
  }, [effectiveAgentId, navigate, refreshChatSessions]);

  const [primary, ...secondary] = IS_PUBLIC_DEMO ? DEMO_PROMPTS : [];
  const blockedReason = loadingAgents
    ? "正在连接公开资料助手…"
    : agentsFailed || !agentReady
      ? "暂时无法连接公开资料助手，请重试后再提问。"
      : "";

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex min-h-14 shrink-0 flex-wrap items-center gap-2 border-b-2 border-dashed border-sketch bg-white/70 px-4 py-2 backdrop-blur-sm sm:px-8">
        {IS_PUBLIC_DEMO ? (
          <span className="inline-flex min-w-0 items-center gap-2 text-sm font-semibold text-ink" aria-live="polite">
            <span aria-hidden="true">{loadingAgents ? "…" : getAgentEmoji(effectiveAgentId)}</span>
            <span className="truncate">
              {loadingAgents
                ? "正在连接助手…"
                : agents.find((agent) => agent.id === effectiveAgentId)?.name ?? "公开资料助手"}
            </span>
          </span>
        ) : (
          <Suspense fallback={<span className="text-sm text-slate-500">正在准备助手选择…</span>}>
            <AgentPicker agents={agents} value={effectiveAgentId} loading={!!loadingAgents} onChange={setSelectedAgentId} />
          </Suspense>
        )}
        <span className="flex-1" />
        {IS_PUBLIC_DEMO && <Tag color="magenta" className="m-0">免登录体验</Tag>}
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-4 py-6 sm:px-8 sm:py-10">
        <div className="mx-auto w-full max-w-2xl">
          <div className="animate-pop-in">
            <div className="mb-3 flex items-center gap-2">
              <ExoBrainLogo className="h-9 w-9 rounded-xl shadow-doodle" />
              <span className="text-xs font-bold tracking-[0.22em] text-sketch0">EXOBRAIN ✎</span>
            </div>
            <Title level={1} className="mb-2! text-2xl! leading-tight! text-ink! sm:text-3xl!">
              {IS_PUBLIC_DEMO ? "问一个问题，查看答案背后的原文" : "开始新的对话"}
            </Title>
            <Text type="secondary" className="block max-w-xl text-sm leading-6 sm:text-base sm:leading-7">
              {IS_PUBLIC_DEMO
                ? "检索公开资料，阅读结论，点击出处核对。"
                : "选择智能体助手，连接知识与工具，开始与 ExoBrain 对话。"}
            </Text>
          </div>

          {IS_PUBLIC_DEMO ? (
            <div className="mt-6">
              {blockedReason && (
                <Alert
                  className="mb-4"
                  type={loadingAgents ? "info" : "warning"}
                  showIcon
                  message={blockedReason}
                  action={!loadingAgents && onRetryAgents ? (
                    <Button size="small" icon={<ReloadOutlined />} onClick={onRetryAgents}>
                      重试
                    </Button>
                  ) : undefined}
                />
              )}

              <button
                type="button"
                onClick={() => void startQuestion(primary.prompt)}
                disabled={!agentReady || busy}
                aria-label={`直接发送推荐问题：${primary.title}`}
                style={{ animationDelay: "40ms" }}
                className="group doodle-card flex min-h-14 w-full animate-pop-in cursor-pointer items-center gap-3 px-4 py-3 text-left disabled:cursor-not-allowed disabled:opacity-60"
              >
                <span className="min-w-0 flex-1">
                  <span className="block text-base font-bold text-ink sm:text-lg">{primary.title}</span>
                  <span className="mt-0.5 block text-xs leading-5 text-slate-500 sm:text-sm">
                    {pendingPrompt === primary.prompt ? "正在打开会话并发送…" : primary.description}
                  </span>
                </span>
                <span className="flex shrink-0 items-center gap-1 text-xs font-semibold text-sketch0">
                  {pendingPrompt === primary.prompt ? "发送中" : "直接提问"}
                  <ArrowRightOutlined className="transition-transform duration-200 group-hover:translate-x-0.5" />
                </span>
              </button>

              <div className="mt-2 flex flex-col gap-2">
                {secondary.map((item, index) => (
                  <button
                    key={item.title}
                    type="button"
                    onClick={() => void startQuestion(item.prompt)}
                    disabled={!agentReady || busy}
                    aria-label={`直接发送推荐问题：${item.title}`}
                    style={{ animationDelay: `${100 + index * 50}ms` }}
                    className="flex min-h-11 animate-pop-in cursor-pointer items-center gap-2 rounded-xl border border-sketch bg-white/70 px-3 py-2 text-left transition-[background-color,border-color,transform] duration-150 ease-out hover:-translate-y-px hover:border-pencil/40 hover:bg-white disabled:cursor-not-allowed disabled:opacity-60"
                  >
                    <span className="min-w-0 flex-1 truncate text-sm font-semibold text-ink">{item.title}</span>
                    <span className="shrink-0 text-xs text-slate-400">直接提问</span>
                    <ArrowRightOutlined className="shrink-0 text-xs text-slate-400" />
                  </button>
                ))}
              </div>

              <p className="mt-2 text-xs leading-5 text-slate-500">
                从推荐问题开始，或在下方输入你想了解的内容。
              </p>

              <div className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-1">
                <button
                  type="button"
                  onClick={() => navigate("/showcase")}
                  className="inline-flex min-h-9 items-center gap-1 text-xs font-semibold text-sketch0 hover:underline"
                >
                  <HistoryOutlined /> 查看真实示例对话
                </button>
                <button
                  type="button"
                  onClick={() => navigate("/knowledge-base")}
                  className="inline-flex min-h-9 items-center gap-1 text-xs font-semibold text-sketch0 hover:underline"
                >
                  <BookOutlined /> 浏览公开资料
                </button>
              </div>
            </div>
          ) : (
            <div className="mt-6 rounded-2xl border border-sketch bg-white/70 p-5 text-sm leading-7 text-slate-500">
              在左侧管理智能体和知识库，在对话中查看工具调用与检索结果。
            </div>
          )}
        </div>
      </div>

      <div
        className="shrink-0 border-t-2 border-dashed border-sketch bg-white/80 backdrop-blur-sm"
        style={{ paddingBottom: "max(0.75rem, env(safe-area-inset-bottom))" }}
      >
        <div className="mx-auto w-full max-w-2xl px-4 pt-3 sm:px-8">
          <NativeComposer
            value={message}
            onChange={setMessage}
            onSubmit={(value) => { void startQuestion(value); }}
            loading={busy}
            disabled={!agentReady}
            sendLabel="发送问题"
            placeholder={
              loadingAgents ? "正在连接助手…" : agentReady ? "输入你的问题…" : "助手暂时不可用"
            }
          />
          <p className="mt-1.5 text-center text-xs leading-5 text-slate-400">
            {IS_PUBLIC_DEMO ? "回答会标注来源；工具调用与执行过程可展开查看。" : "Enter 发送，Shift + Enter 换行。"}
          </p>
        </div>
      </div>
    </div>
  );
}
