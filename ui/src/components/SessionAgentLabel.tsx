import { RobotOutlined } from "@ant-design/icons";
import type { AgentVO } from "../api/api.ts";

export interface SessionAgentCatalog {
  agents: Pick<AgentVO, "id" | "name">[];
  loading: boolean;
  loadFailed: boolean;
}

interface SessionAgentLabelProps {
  agentId?: string | null;
  catalog: SessionAgentCatalog;
  compact?: boolean;
  className?: string;
}

export default function SessionAgentLabel({ agentId, catalog, compact = false, className = "" }: SessionAgentLabelProps) {
  const savedAgentId = agentId?.trim();
  const agent = savedAgentId ? catalog.agents.find((candidate) => candidate.id === savedAgentId) : undefined;
  let name: string;
  let detail: string;

  if (!savedAgentId) {
    name = "未记录或已移除";
    detail = "会话未记录 Agent 绑定，或关联 Agent 已被移除。";
  } else if (catalog.loading) {
    name = "加载中…";
    detail = "正在获取此会话关联的 Agent 名称。";
  } else if (catalog.loadFailed) {
    name = "信息加载失败";
    detail = "暂时无法获取 Agent 列表，请刷新页面重试。";
  } else if (!agent) {
    name = "不存在或不可见";
    detail = "当前可见的 Agent 列表中没有此会话保存的关联。";
  } else {
    name = agent.name.trim() || "未命名";
    detail = `此会话关联的 Agent：${name}`;
  }

  return (
    <span
      className={`inline-flex max-w-full min-w-0 items-center gap-1.5 text-slate-500 ${className}`}
      title={savedAgentId ? `${detail}\nAgent ID：${savedAgentId}` : detail}
    >
      <RobotOutlined aria-hidden className="shrink-0" />
      <span className={compact ? "min-w-0 truncate" : "min-w-0 break-words"}>Agent：{name}</span>
    </span>
  );
}
