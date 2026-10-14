import React, { useState } from "react";
import { HistoryOutlined, LoginOutlined, TeamOutlined, SlidersOutlined } from "@ant-design/icons";
import { Button, Tabs, type TabsProps } from "antd";
import { useLocation, useNavigate } from "react-router-dom";
import AgentTabContent from "./tabs/AgentTabContent.tsx";
import AddAgentModal from "./modals/AddAgentModal.tsx";
import ChatTabContent from "./tabs/ChatTabContent.tsx";
import KnowledgeBaseTabContent from "./tabs/KnowledgeBaseTabContent.tsx";
import AddKnowledgeBaseModal from "./modals/AddKnowledgeBaseModal.tsx";
import { useAgents } from "../hooks/useAgents.ts";
import { useKnowledgeBases } from "../hooks/useKnowledgeBases.ts";
import { IS_PUBLIC_DEMO } from "../config.ts";
import ExoBrainLogo from "./ExoBrainLogo.tsx";
import { useAccount } from "../contexts/accountState.ts";
import AccountMenu from "./AccountMenu.tsx";

interface SideMenuProps {
  children?: React.ReactNode;
  onNavigate?: () => void;
}

const SideMenu: React.FC<SideMenuProps> = ({ onNavigate }) => {
  const navigate = useNavigate();
  const location = useLocation();
  const account = useAccount();

  const [isAddAgentModalOpen, setIsAddAgentModalOpen] = useState(false);
  const toggleAddAgentModal = () => {
    setIsAddAgentModalOpen(!isAddAgentModalOpen);
    setEditingAgent(null);
  };

  const [editingAgent, setEditingAgent] = useState<
    import("../api/api.ts").AgentVO | null
  >(null);

  /**
   * 添加知识库模态框状态
   */
  const [isAddKnowledgeBaseModalOpen, setIsAddKnowledgeBaseModalOpen] =
    useState(false);
  const toggleAddKnowledgeBaseModal = () => {
    setIsAddKnowledgeBaseModalOpen(!isAddKnowledgeBaseModalOpen);
  };
  const { agents, createAgentHandle, deleteAgentHandle, updateAgentHandle } =
    useAgents();

  const [selectedTab, setSelectedTab] = useState<{ path: string; key: string } | null>(null);
  const routeTab = (() => {
    if (location.pathname.startsWith("/agent")) return "agent";
    if (location.pathname.startsWith("/knowledge-base")) return "knowledgeBase";
    if (location.pathname.startsWith("/chat")) return "chat";
    return IS_PUBLIC_DEMO ? "chat" : "agent";
  })();
  const activeKey = selectedTab?.path === location.pathname ? selectedTab.key : routeTab;

  const goTo = (path: string, state?: Record<string, string>) => {
    navigate(path, { state });
    onNavigate?.();
  };

  const { knowledgeBases, createKnowledgeBaseHandle } = useKnowledgeBases();

  // 处理标签页切换
  const handleTabChange = (key: string) => {
    setSelectedTab({ path: location.pathname, key });
  };

  const items: TabsProps["items"] = [
    {
      key: "agent",
      label: <span className="select-none">{IS_PUBLIC_DEMO ? "助手" : "智能体助手"}</span>,
      children: (
        <AgentTabContent
          agents={agents}
          onSelectAgent={(agentId) => goTo("/chat", { selectedAgentId: agentId })}
          onCreateAgentClick={IS_PUBLIC_DEMO ? undefined : toggleAddAgentModal}
          onEditAgent={IS_PUBLIC_DEMO ? undefined : (agent) => {
            setEditingAgent(agent);
            setIsAddAgentModalOpen(true);
          }}
          onDeleteAgent={IS_PUBLIC_DEMO ? undefined : deleteAgentHandle}
        />
      ),
    },
    {
      key: "chat",
      label: <span className="select-none">{IS_PUBLIC_DEMO ? "我的记录" : "聊天记录"}</span>,
      children: <ChatTabContent onNavigate={onNavigate} />,
    },
    {
      key: "knowledgeBase",
      label: <span className="select-none">{IS_PUBLIC_DEMO ? "公开资料" : "知识库"}</span>,
      children: (
        <KnowledgeBaseTabContent
          knowledgeBases={knowledgeBases}
          onCreateKnowledgeBaseClick={IS_PUBLIC_DEMO ? undefined : toggleAddKnowledgeBaseModal}
          onSelectKnowledgeBase={(knowledgeBaseId) => {
            goTo(`/knowledge-base/${knowledgeBaseId}`);
          }}
        />
      ),
    },
  ];

  return (
    <div className="px-4 flex flex-col h-full">
      <div className="min-h-20 w-full shrink-0 flex items-center border-b border-slate-200">
        <div className="flex items-center gap-2.5">
          <button type="button" onClick={() => goTo("/")} className="flex cursor-pointer items-center gap-2.5 rounded-lg text-left" aria-label="ExoBrain 首页">
            <ExoBrainLogo />
            <span className="text-xl font-semibold tracking-tight text-slate-900">ExoBrain</span>
          </button>
          {IS_PUBLIC_DEMO && (
            <span className="text-xs text-indigo-600 bg-indigo-50 rounded px-1.5 py-0.5">
              公开演示
            </span>
          )}
        </div>
      </div>
      {IS_PUBLIC_DEMO && (
        <Button className="mt-4! shrink-0" icon={<HistoryOutlined />} onClick={() => goTo("/showcase")}>
          示例对话
        </Button>
      )}
      {!IS_PUBLIC_DEMO && account?.user?.role === "ADMIN" && (
        <div className="mt-4 grid shrink-0 grid-cols-2 gap-2">
          <Button type={location.pathname === "/users" ? "primary" : "default"} icon={<TeamOutlined />} onClick={() => goTo("/users")}>用户管理</Button>
          <Button type={location.pathname === "/models" ? "primary" : "default"} icon={<SlidersOutlined />} onClick={() => goTo("/models")}>系统模型</Button>
        </div>
      )}
      <div className="flex-1 min-h-0 flex flex-col">
        <Tabs
          activeKey={activeKey}
          onChange={handleTabChange}
          items={items}
        />
      </div>
      {IS_PUBLIC_DEMO ? <div className="shrink-0 border-t border-slate-200 py-3"><Button block href="/admin/" icon={<LoginOutlined />}>账号登录</Button></div> : <AccountMenu />}
      {!IS_PUBLIC_DEMO && <AddAgentModal
        open={isAddAgentModalOpen}
        onClose={toggleAddAgentModal}
        createAgentHandle={createAgentHandle}
        updateAgentHandle={updateAgentHandle}
        editingAgent={editingAgent}
      />}
      {!IS_PUBLIC_DEMO && <AddKnowledgeBaseModal
        open={isAddKnowledgeBaseModalOpen}
        onClose={toggleAddKnowledgeBaseModal}
        createKnowledgeBaseHandle={createKnowledgeBaseHandle}
      />}
    </div>
  );
};

export default SideMenu;
