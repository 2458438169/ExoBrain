import React, { useMemo } from "react";
import { useNavigate, useLocation } from "react-router-dom";
import { Button, Divider, Popconfirm } from "antd";
import {
  PlusOutlined,
  MessageOutlined,
  DeleteOutlined,
} from "@ant-design/icons";
import { useChatSessions } from "../../hooks/useChatSessions.ts";
import { useAgents } from "../../hooks/useAgents.ts";
import { IS_PUBLIC_DEMO } from "../../config.ts";
import { showRequestError } from "../../api/http.ts";
import SessionAgentLabel from "../SessionAgentLabel.tsx";

const ChatTabContent: React.FC<{ onNavigate?: () => void }> = ({ onNavigate }) => {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const { chatSessions, loading, deleteChatSession } = useChatSessions();
  const agentCatalog = useAgents();
  const { agents } = agentCatalog;

  // 创建 agentId 到 agent 的映射
  const agentMap = useMemo(() => {
    const map = new Map<string, string>();
    agents.forEach((agent) => {
      map.set(agent.id, agent.name);
    });
    return map;
  }, [agents]);

  const handleCreateNewChat = () => {
    navigate("/chat");
    onNavigate?.();
  };

  const handleSelectChatSession = (chatSessionId: string) => {
    navigate(`/chat/${chatSessionId}`);
    onNavigate?.();
  };

  const handleDeleteChatSession = async (chatSessionId: string) => {
    try {
      await deleteChatSession(chatSessionId);
      if (pathname === `/chat/${chatSessionId}`) navigate("/chat");
    } catch (error) {
      showRequestError(error, "删除会话失败，请重试");
    }
  };

  // 格式化标题显示
  const getDisplayTitle = (session: { title?: string; agentId: string | null }) => {
    if (session.title) {
      return session.title;
    }
    const agentName = session.agentId ? agentMap.get(session.agentId) : undefined;
    return agentName ? `与 ${agentName} 的对话` : "新对话";
  };

  return (
    <div className="flex flex-col h-full">
      <Button
        color="geekblue"
        variant="filled"
        icon={<PlusOutlined />}
        onClick={handleCreateNewChat}
        className="w-full"
      >
        新聊天
      </Button>
      {IS_PUBLIC_DEMO && <p className="mt-3 text-xs leading-5 text-slate-500">仅显示当前浏览器的个人体验记录。清除站点数据后将无法找回。</p>}
      <Divider />
      <div className="flex-1 min-h-0 overflow-y-auto bg-sketch/60 rounded-2xl">
        {loading ? (
          <div className="flex flex-col items-center justify-center h-full text-pencil/40">
            <p className="text-sm">加载中...</p>
          </div>
        ) : chatSessions.length === 0 ? (
          <div className="px-3 py-4 text-xs leading-5 text-pencil/50">
            还没有记录。第一次提问后，这里会出现只有你能看到的体验记录。
          </div>
        ) : (
          <div className="space-y-1.5 p-1.5">
            {chatSessions.map((session) => (
              <div
                key={session.id}
                onClick={() => handleSelectChatSession(session.id)}
                className="group relative w-full cursor-pointer rounded-xl border border-transparent bg-white px-3 py-2.5 shadow-[2px_2px_0_0_rgba(43, 39, 36,0.08)] transition-[transform,box-shadow,border-color] duration-200 hover:-translate-y-0.5 hover:border-sketch hover:shadow-[3px_3px_0_0_rgba(43, 39, 36,0.16)]"
              >
                <div className="flex items-start gap-3">
                  <div className="w-8 h-8 rounded-xl bg-gradient-to-br from-sketch to-sketch flex items-center justify-center shrink-0 text-lg mt-0.5 text-ink transition-transform duration-200 group-hover:scale-110 group-hover:-rotate-6">
                    <MessageOutlined />
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="font-semibold text-ink truncate">
                      {getDisplayTitle(session)}
                    </div>
                    <SessionAgentLabel agentId={session.agentId} catalog={agentCatalog} compact className="mt-1 text-xs" />
                  </div>
                  <div onClick={(e) => e.stopPropagation()}>
                    <Popconfirm
                      title="确定要删除这条聊天记录吗？"
                      description="删除后将无法恢复"
                      onConfirm={() => handleDeleteChatSession(session.id)}
                      okText="确定"
                      cancelText="取消"
                    >
                      <Button
                        type="text"
                        size="small"
                        icon={<DeleteOutlined />}
                        aria-label="删除聊天记录"
                        className="opacity-60 group-hover:opacity-100 focus-visible:opacity-100 transition-opacity shrink-0"
                        onClick={(e) => e.stopPropagation()}
                        danger
                      />
                    </Popconfirm>
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default ChatTabContent;
