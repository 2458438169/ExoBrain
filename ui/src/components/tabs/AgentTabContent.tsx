import React, { useMemo } from "react";
import { Button, Divider, Dropdown, Modal } from "antd";
import type { MenuProps } from "antd";
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  MoreOutlined,
} from "@ant-design/icons";
import type { AgentVO } from "../../api/api.ts";
import { formatDateTime, getAgentEmoji } from "../../utils";

interface AgentTabContentProps {
  agents: AgentVO[];
  onCreateAgentClick?: () => void;
  onSelectAgent: (agentId: string) => void;
  onEditAgent?: (agent: AgentVO) => void;
  onDeleteAgent?: (agentId: string) => void;
}

const AgentTabContent: React.FC<AgentTabContentProps> = ({
  agents,
  onCreateAgentClick,
  onSelectAgent,
  onEditAgent,
  onDeleteAgent,
}) => {
  const [modal, modalHolder] = Modal.useModal();
  // 为每个 agent 生成 emoji
  const agentsWithEmoji = useMemo(() => {
    return agents.map((agent) => ({
      ...agent,
      emoji: getAgentEmoji(agent.id),
    }));
  }, [agents]);

  // 创建右键菜单
  const getContextMenuItems = (agent: AgentVO): MenuProps["items"] => {
    const items: MenuProps["items"] = [];
    if (agent.readOnly) return items;

    if (onEditAgent) {
      items.push({
        key: "edit",
        label: "编辑",
        icon: <EditOutlined />,
        onClick: (e) => {
          e.domEvent.stopPropagation();
          onEditAgent(agent);
        },
      });
    }

    if (onDeleteAgent) {
      items.push({
        key: "delete",
        label: "删除",
        icon: <DeleteOutlined />,
        danger: true,
        onClick: (e) => {
          e.domEvent.stopPropagation();
          modal.confirm({
            title: "确定要删除这个智能体吗？",
            content: "删除后将无法恢复",
            okText: "确定",
            cancelText: "取消",
            okType: "danger",
            onOk: () => {
              onDeleteAgent(agent.id);
            },
          });
        },
      });
    }

    return items;
  };

  return (
    <div className="flex flex-col h-full">
      {modalHolder}
      {onCreateAgentClick && <><Button
        color="geekblue"
        variant="filled"
        icon={<PlusOutlined />}
        onClick={onCreateAgentClick}
        className="w-full"
      >
        智能体助手
      </Button>
      <Divider /></>}
      <div className="flex-1 overflow-y-auto bg-sketch/50 rounded-2xl p-1.5">
        {agents.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-sketch">
            <p className="text-sm">暂无智能体</p>
            {onCreateAgentClick && <p className="text-xs mt-1">点击上方按钮添加</p>}
          </div>
        ) : (
          <div className="space-y-1.5">
            {agentsWithEmoji.map((agent) => {
              const menuItems = getContextMenuItems(agent);
              const hasMenu = menuItems && menuItems.length > 0;
              return (
                <div
                  key={agent.id}
                  onClick={() => onSelectAgent(agent.id)}
                  className="group relative w-full cursor-pointer rounded-xl border border-transparent bg-white px-3 py-3 shadow-[2px_2px_0_0_rgba(43, 39, 36,0.08)] transition-[transform,box-shadow,border-color,background-color] duration-200 hover:-translate-y-0.5 hover:border-sketch hover:shadow-[3px_3px_0_0_rgba(43, 39, 36,0.16)]"
                >
                  <div className="flex items-start gap-3">
                    <div className="w-8 h-8 rounded-xl bg-gradient-to-br from-sketch to-sketch flex items-center justify-center shrink-0 text-lg mt-0.5 transition-transform duration-200 group-hover:scale-110 group-hover:rotate-6">
                      {agent.emoji}
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="font-semibold text-ink truncate">
                        {agent.name}
                      </div>
                      {agent.readOnly && <div className="mt-1 text-xs text-sketch0">公开助手</div>}
                      {agent.description && (
                        <div className="text-xs text-slate-500 mt-1 line-clamp-1">
                          {agent.description}
                        </div>
                      )}
                      {agent.updatedAt && (
                        <div className="text-xs text-slate-400 mt-1">
                          {formatDateTime(agent.updatedAt)}
                        </div>
                      )}
                    </div>
                    {hasMenu && (
                      <div
                        onClick={(e) => e.stopPropagation()}
                        onContextMenu={(e) => e.stopPropagation()}
                        className="shrink-0 opacity-100 transition-opacity md:opacity-0 md:group-hover:opacity-100 md:focus-within:opacity-100"
                      >
                        <Dropdown
                          menu={{ items: menuItems }}
                          trigger={["contextMenu", "click"]}
                          placement="bottomRight"
                        >
                          <Button
                            type="text"
                            size="small"
                            icon={<MoreOutlined />}
                            aria-label={`管理助手 ${agent.name}`}
                            onClick={(e) => e.stopPropagation()}
                            className="text-gray-400 hover:text-gray-600"
                          />
                        </Dropdown>
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
};

export default AgentTabContent;
