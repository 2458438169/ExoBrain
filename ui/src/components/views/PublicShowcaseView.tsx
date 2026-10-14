import { useEffect, useState } from "react";
import { Button, Empty, Spin, Tag, Typography } from "antd";
import { ArrowLeftOutlined, ArrowRightOutlined, HistoryOutlined, MessageOutlined } from "@ant-design/icons";
import { useNavigate, useParams } from "react-router-dom";
import { getDemoShowcase, getDemoShowcases, type DemoShowcase } from "../../api/demo.ts";
import type { ChatSessionVO } from "../../api/api.ts";
import { showRequestError } from "../../api/http.ts";
import AgentChatHistory from "./agentChatView/AgentChatHistory.tsx";
import { useAgents } from "../../hooks/useAgents.ts";
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
          查看基于公开资料完成的演示问答，展开工具调用与返回结果，了解 ExoBrain 如何检索资料并形成回答。
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
                className="flex cursor-pointer items-center gap-4 rounded-2xl border border-slate-200 bg-slate-50/60 p-5 text-left transition hover:border-indigo-300 hover:bg-indigo-50/50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-500"
                onClick={() => navigate(`/showcase/${showcase.id}`)}
              >
                <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-indigo-100 text-lg text-indigo-600"><HistoryOutlined /></span>
                <span className="min-w-0 flex-1">
                  <span className="mb-1 block text-xs text-slate-400">示例对话 {String(index + 1).padStart(2, "0")}</span>
                  <span className="block font-medium text-slate-900">{showcase.title || "示例对话"}</span>
                  <SessionAgentLabel agentId={showcase.agentId} catalog={agentCatalog} compact className="mt-1 text-xs" />
                  <span className="mt-1 block text-xs text-slate-500">查看问答、工具调用与结果</span>
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
  const [showcase, setShowcase] = useState<DemoShowcase | null>(null);
  const [loading, setLoading] = useState(true);

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

  return (
    <div className="flex h-full min-h-0 flex-col bg-white">
      <div className="shrink-0 border-b border-slate-100 px-5 py-4 sm:px-8">
        <Button type="text" size="small" icon={<ArrowLeftOutlined />} onClick={() => navigate("/showcase")}>全部示例对话</Button>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Tag color="geekblue">示例对话 · 只读</Tag>
          <span className="text-sm font-medium text-slate-900">{showcase?.title || "示例对话"}</span>
        </div>
        {showcase && <SessionAgentLabel agentId={showcase.agentId} catalog={agentCatalog} className="mt-2 text-sm" />}
        <p className="mt-2 text-xs text-slate-500">点击「工具调用」和「工具结果」可查看检索参数、资料来源与实际返回内容。</p>
      </div>
      {loading ? (
        <div className="flex flex-1 items-center justify-center"><Spin /></div>
      ) : showcase ? (
        <AgentChatHistory messages={showcase.chatMessages} autoScroll={false} />
      ) : (
        <div className="flex flex-1 items-center justify-center"><Empty description="示例对话不存在或尚未公开" /></div>
      )}
      <div className="flex shrink-0 flex-wrap items-center justify-center gap-3 border-t border-slate-100 p-4">
        <span className="text-xs text-slate-500">也可以用同一个问题开始自己的对话。</span>
        <Button type="primary" icon={<MessageOutlined />} onClick={() => navigate("/chat", { state: { suggestedPrompt: firstQuestion } })}>
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
