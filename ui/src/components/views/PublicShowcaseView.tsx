import { useCallback, useEffect, useState } from "react";
import { Button, Empty, Spin, Tag, Typography } from "antd";
import { ArrowLeftOutlined, ArrowRightOutlined, HistoryOutlined, MessageOutlined } from "@ant-design/icons";
import { useNavigate, useParams } from "react-router-dom";
import { getDemoShowcase, getDemoShowcases, type DemoShowcase } from "../../api/demo.ts";
import { createChatSession, type ChatSessionVO } from "../../api/api.ts";
import { showRequestError } from "../../api/http.ts";
import AgentChatHistory from "./agentChatView/AgentChatHistory.tsx";
import { useAgents } from "../../hooks/useAgents.ts";
import { useChatSessions } from "../../hooks/useChatSessions.ts";
import SessionAgentLabel, { type SessionAgentCatalog } from "../SessionAgentLabel.tsx";

const { Title, Paragraph } = Typography;

function ShowcaseList({ agentCatalog }: { agentCatalog: SessionAgentCatalog }) {
  const navigate = useNavigate();
  const [showcases, setShowcases] = useState<ChatSessionVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;
    void getDemoShowcases().then((response) => {
      if (active) setShowcases(response.showcases);
    }).catch((error) => {
      if (active) {
        setFailed(true);
        showRequestError(error, "示例对话加载失败");
      }
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

  return (
    <div className="h-full overflow-y-auto bg-white p-5 sm:p-8">
      <div className="mx-auto max-w-4xl">
        <Tag color="geekblue">公开示例</Tag>
        <Title level={2} className="mt-3!">示例对话</Title>
        <Paragraph type="secondary" className="max-w-2xl leading-7">
          查看基于公开资料完成的演示问答：默认先读答案，需要时再展开来源与执行过程。查看示例不会触发模型请求。
        </Paragraph>
        <p className="mb-6 text-sm text-slate-500">这里仅展示已公开的示例。你自己的对话保存在「我的记录」中。</p>
        {loading ? (
          <div className="py-16 text-center"><Spin tip="正在加载示例对话…"><div className="h-12" /></Spin></div>
        ) : showcases.length === 0 ? (
          <Empty description={failed ? "示例对话暂时无法加载" : "暂无示例对话"}>
            <Button type="primary" onClick={() => navigate("/chat")}>开始自己的体验</Button>
          </Empty>
        ) : (
          <div className="grid gap-4">
            {showcases.map((showcase, index) => (
              <button
                key={showcase.id}
                type="button"
                className="flex cursor-pointer items-center gap-4 rounded-2xl border border-slate-200 bg-slate-50/60 p-5 text-left transition-[transform,border-color,background-color] duration-150 ease-out hover:-translate-y-px hover:border-indigo-300 hover:bg-indigo-50/50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-500"
                onClick={() => navigate(`/showcase/${showcase.id}`)}
              >
                <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-indigo-100 text-lg text-indigo-600"><HistoryOutlined /></span>
                <span className="min-w-0 flex-1">
                  <span className="mb-1 block text-xs text-slate-400">示例对话 {String(index + 1).padStart(2, "0")}</span>
                  <span className="block font-medium text-slate-900">{showcase.title || "示例对话"}</span>
                  <SessionAgentLabel agentId={showcase.agentId} catalog={agentCatalog} compact className="mt-1 text-xs" />
                  <span className="mt-1 block text-xs text-slate-500">查看问题、答案、来源与执行过程</span>
                </span>
                <ArrowRightOutlined className="text-indigo-500" />
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function ShowcaseDetail({ id, agentCatalog }: { id: string; agentCatalog: SessionAgentCatalog }) {
  const navigate = useNavigate();
  const { refreshChatSessions } = useChatSessions();
  const [showcase, setShowcase] = useState<DemoShowcase | null>(null);
  const [loading, setLoading] = useState(true);
  const [starting, setStarting] = useState(false);

  useEffect(() => {
    let active = true;
    void getDemoShowcase(id).then((response) => {
      if (active) setShowcase(response);
    }).catch((error) => {
      if (active) showRequestError(error, "示例对话加载失败");
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [id]);

  const firstQuestion = showcase?.chatMessages.find((message) => message.role === "user")?.content;

  /**
   * 「亲自试一试」直接开始一条新的个人运行：创建会话后把问题作为首条发送意图交给聊天页，
   * 由同一个发送协调器提交。查看示例本身不会创建运行，也不会订阅示例的 SSE。
   */
  const startOwnRun = useCallback(async () => {
    const agentId = showcase?.agentId ?? agentCatalog.agents[0]?.id;
    if (!agentId || !firstQuestion || starting) return;
    setStarting(true);
    try {
      const created = await createChatSession({ agentId, title: firstQuestion.slice(0, 40) });
      navigate(`/chat/${created.chatSessionId}`, {
        state: { initialMessage: { content: firstQuestion, agentId } },
      });
      void refreshChatSessions().catch((error) => showRequestError(error, "聊天记录刷新失败"));
    } catch (error) {
      showRequestError(error, "创建聊天会话失败，请重试");
    } finally {
      setStarting(false);
    }
  }, [agentCatalog.agents, firstQuestion, navigate, refreshChatSessions, showcase?.agentId, starting]);

  return (
    <div className="flex h-full min-h-0 min-w-0 flex-col bg-white">
      <div className="shrink-0 border-b border-slate-100 px-4 py-3 sm:px-8">
        <Button type="text" size="small" icon={<ArrowLeftOutlined />} onClick={() => navigate("/showcase")}>全部示例对话</Button>
        <div className="mt-2 flex flex-wrap items-center gap-2">
          <Tag color="geekblue" className="m-0">示例对话 · 只读</Tag>
          <span className="min-w-0 break-words text-sm font-medium text-slate-900">{showcase?.title || "示例对话"}</span>
        </div>
        {showcase && <SessionAgentLabel agentId={showcase.agentId} catalog={agentCatalog} className="mt-1 text-xs" />}
        <p className="mt-1 text-xs text-slate-500">这是只读示例：可以先读答案，需要时再展开来源与执行过程。这里不能取消或继续追问。</p>
      </div>
      {loading ? (
        <div className="flex flex-1 items-center justify-center"><Spin /></div>
      ) : showcase ? (
        <AgentChatHistory messages={showcase.chatMessages} autoScroll={false} />
      ) : (
        <div className="flex flex-1 items-center justify-center"><Empty description="示例对话不存在或尚未公开" /></div>
      )}
      <div className="flex shrink-0 flex-wrap items-center justify-center gap-3 border-t border-slate-100 p-4">
        <span className="text-xs text-slate-500">用同一个问题开始自己的对话，会新建一条只有你能看到的运行。</span>
        <Button
          type="primary"
          icon={<MessageOutlined />}
          loading={starting}
          disabled={!firstQuestion}
          onClick={() => void startOwnRun()}
        >
          亲自试一试
        </Button>
      </div>
    </div>
  );
}

export default function PublicShowcaseView() {
  const { showcaseId } = useParams<{ showcaseId: string }>();
  const agentCatalog = useAgents();
  return showcaseId
    ? <ShowcaseDetail key={showcaseId} id={showcaseId} agentCatalog={agentCatalog} />
    : <ShowcaseList agentCatalog={agentCatalog} />;
}
