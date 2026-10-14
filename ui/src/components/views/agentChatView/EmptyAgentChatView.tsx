import { useMemo, useState } from "react";
import { Button, Select, Tag, Typography } from "antd";
import { ArrowUpOutlined, BookOutlined, DownOutlined, HistoryOutlined } from "@ant-design/icons";
import { Sender } from "@ant-design/x";
import { useLocation, useNavigate } from "react-router-dom";
import { type AgentVO, createChatSession } from "../../../api/api.ts";
import { getAgentEmoji } from "../../../utils";
import { useChatSessions } from "../../../hooks/useChatSessions.ts";
import { DEMO_PROMPTS, IS_PUBLIC_DEMO } from "../../../config.ts";
import { showRequestError } from "../../../api/http.ts";
import ExoBrainLogo from "../../ExoBrainLogo.tsx";

const { Title, Text } = Typography;

interface EmptyAgentChatViewProps {
  agents: AgentVO[];
  loadingAgents?: boolean;
}

export default function EmptyAgentChatView({ agents, loadingAgents }: EmptyAgentChatViewProps) {
  const { state } = useLocation();
  const [message, setMessage] = useState(() =>
    typeof state?.suggestedPrompt === "string" ? state.suggestedPrompt : "",
  );
  const [loading, setLoading] = useState(false);
  const [selectedAgentId, setSelectedAgentId] = useState<string | null>(() => typeof state?.selectedAgentId === "string" ? state.selectedAgentId : null);
  const navigate = useNavigate();
  const { refreshChatSessions } = useChatSessions();

  const effectiveAgentId = useMemo(() => {
    return agents.find((agent) => agent.id === selectedAgentId)?.id ?? agents[0]?.id ?? null;
  }, [selectedAgentId, agents]);

  const startChat = async () => {
    const content = message.trim();
    if (!effectiveAgentId || !content || loading) return;
    setLoading(true);
    try {
      const response = await createChatSession({
        agentId: effectiveAgentId,
        title: content.slice(0, 40),
      });
      // 进入会话、建立 SSE 后再发送首条消息，避免丢失 AI 回复。
      navigate(`/chat/${response.chatSessionId}`, {
        state: { initialMessage: { content, agentId: effectiveAgentId } },
      });
      void refreshChatSessions().catch((error) => showRequestError(error, "聊天记录刷新失败"));
    } catch (error) {
      showRequestError(error, "创建聊天会话失败，请重试");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="flex h-full min-h-0 flex-col bg-white">
      <div className="flex min-h-16 shrink-0 flex-wrap items-center gap-3 border-b border-slate-100 px-5 py-3">
        <Select
          aria-label="选择智能体助手"
          value={effectiveAgentId}
          onChange={setSelectedAgentId}
          loading={loadingAgents}
          disabled={!agents.length}
          style={{ minWidth: 190, maxWidth: "100%" }}
          suffixIcon={<DownOutlined className="text-slate-400" />}
          placeholder={loadingAgents ? "正在连接助手…" : "暂无可用助手"}
          options={agents.map((agent) => ({ value: agent.id, label: `${getAgentEmoji(agent.id)} ${agent.name}` }))}
        />
        {IS_PUBLIC_DEMO && <Tag color="geekblue">免登录体验</Tag>}
      </div>

      <div className="flex min-h-0 flex-1 flex-col overflow-y-auto px-5 py-8 sm:px-8">
        <div className="mx-auto my-auto w-full max-w-3xl">
          <div className="mb-8 text-center">
            <ExoBrainLogo className="mx-auto mb-5 h-16 w-16 shadow-lg shadow-indigo-100" />
            <div className="mb-2 text-xs font-semibold tracking-[0.22em] text-indigo-600">EXOBRAIN</div>
            <Title level={2} className="mb-3! text-slate-900!">
              {IS_PUBLIC_DEMO ? "让知识成为你的第二大脑" : "开始新的对话"}
            </Title>
            <Text type="secondary" className="mx-auto block max-w-xl text-base leading-7">
              {IS_PUBLIC_DEMO
                ? "从公开项目资料出发，体验知识检索、智能问答和真实的工具调用。选择一个问题，或直接在下方提问。"
                : "选择智能体助手，连接知识与工具，开始与 ExoBrain 对话。"}
            </Text>
          </div>

          {IS_PUBLIC_DEMO ? (
            <>
              <div className="grid gap-3 lg:grid-cols-3">
                {DEMO_PROMPTS.map((example, index) => (
                  <button
                    key={example.title}
                    type="button"
                    onClick={() => setMessage(example.prompt)}
                    className="group flex cursor-pointer flex-col rounded-2xl border border-slate-200 bg-slate-50/70 p-5 text-left transition hover:border-indigo-300 hover:bg-indigo-50/60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-500"
                  >
                    <span className="mb-4 text-xs font-medium text-indigo-500">0{index + 1}</span>
                    <span className="mb-2 font-semibold text-slate-900">{example.title}</span>
                    <span className="flex-1 text-sm leading-6 text-slate-500">{example.description}</span>
                    <span className="mt-4 flex items-center gap-1 text-xs text-indigo-600">填入问题 <ArrowUpOutlined /></span>
                  </button>
                ))}
              </div>
              <div className="mt-6 flex flex-wrap justify-center gap-2">
                <Button icon={<BookOutlined />} onClick={() => navigate("/knowledge-base")}>
                  浏览公开知识库
                </Button>
                <Button icon={<HistoryOutlined />} onClick={() => navigate("/showcase")}>
                  查看示例对话
                </Button>
              </div>
              <p className="mx-auto mt-6 max-w-xl text-center text-xs leading-6 text-slate-500">
                示例对话仅展示公开问答。使用同一浏览器可继续查看自己的聊天历史。
              </p>
            </>
          ) : (
            <div className="rounded-2xl border border-slate-100 bg-slate-50 p-6 text-center text-sm leading-7 text-slate-500">
              在左侧管理智能体和知识库，在对话中查看工具调用与检索结果。
            </div>
          )}
        </div>
      </div>

      <div className="shrink-0 border-t border-slate-100 bg-white px-5 py-4 sm:px-8">
        <div className="mx-auto max-w-3xl">
          <Sender
            onSubmit={startChat}
            value={message}
            loading={loading}
            disabled={!effectiveAgentId || loading}
            placeholder={loadingAgents ? "正在连接助手…" : "输入问题，开始与 ExoBrain 对话…"}
            onChange={setMessage}
          />
          {IS_PUBLIC_DEMO && (
            <p className="mt-2 text-center text-xs text-slate-400">知识库回答可展开查看工具调用与文档来源。</p>
          )}
        </div>
      </div>
    </div>
  );
}
