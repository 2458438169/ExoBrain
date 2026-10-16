import React, { useMemo } from "react";
import { Button, Divider } from "antd";
import { PlusOutlined, BookOutlined, LockOutlined, GlobalOutlined } from "@ant-design/icons";
import type { KnowledgeBase } from "../../types";
import { getKnowledgeBaseEmoji } from "../../utils";
import { IS_PUBLIC_DEMO } from "../../config.ts";

interface KnowledgeBaseTabContentProps {
  knowledgeBases: KnowledgeBase[];
  onCreateKnowledgeBaseClick?: () => void;
  onSelectKnowledgeBase?: (knowledgeBaseId: string) => void;
}

const KnowledgeBaseTabContent: React.FC<KnowledgeBaseTabContentProps> = ({
  knowledgeBases,
  onCreateKnowledgeBaseClick,
  onSelectKnowledgeBase,
}) => {
  // 为每个知识库生成 emoji
  const knowledgeBasesWithEmoji = useMemo(() => {
    return knowledgeBases.map((kb) => ({
      ...kb,
      emoji: getKnowledgeBaseEmoji(kb.knowledgeBaseId),
    }));
  }, [knowledgeBases]);
  const groups = IS_PUBLIC_DEMO
    ? [{ title: "公开演示库", publicResource: true, items: knowledgeBasesWithEmoji }]
    : [
      { title: "我的知识库", publicResource: false, items: knowledgeBasesWithEmoji.filter((kb) => !kb.publicResource) },
      { title: "公开演示库", publicResource: true, items: knowledgeBasesWithEmoji.filter((kb) => kb.publicResource) },
    ];

  return (
    <div className="flex flex-col h-full">
      {onCreateKnowledgeBaseClick && <><Button
        color="geekblue"
        variant="filled"
        icon={<PlusOutlined />}
        onClick={onCreateKnowledgeBaseClick}
        className="w-full"
      >
        新建知识库
      </Button>
      <Divider /></>}
      <div className="flex-1 overflow-y-scroll rounded-lg">
        {knowledgeBases.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-gray-400">
            <BookOutlined className="text-4xl mb-2" />
            <p className="text-sm">暂无知识库</p>
            <p className="text-xs mt-1">
              {IS_PUBLIC_DEMO ? "公开演示暂未提供知识库" : "点击上方按钮创建"}
            </p>
          </div>
        ) : (
          <div className="space-y-1.5 p-1.5">
            {groups.map((group) => <section key={group.title} aria-label={group.title} className="mb-4">
              <div className="mb-2 flex items-center gap-1.5 px-1 text-xs font-medium text-slate-500">
                {group.publicResource ? <GlobalOutlined /> : <LockOutlined />}
                <span>{group.title}</span><span>({group.items.length})</span>
              </div>
              {group.items.length === 0 && <p className="rounded-lg bg-slate-50 px-3 py-4 text-xs leading-6 text-slate-400">
                {group.publicResource ? "暂无公开演示资料" : "还没有私有知识库。新建后仅当前账号可见。"}
              </p>}
              {group.items.map((kb) => (
              <button type="button" aria-label={`打开知识库 ${kb.name}`}
                key={kb.knowledgeBaseId}
                onClick={() => onSelectKnowledgeBase?.(kb.knowledgeBaseId)}
                className="w-full text-left px-3 py-2.5 rounded-lg bg-white cursor-pointer transition-all hover:bg-gray-100 hover:shadow-sm"
              >
                <div className="flex items-start gap-3">
                  <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-blue-200 to-purple-200 flex items-center justify-center shrink-0 text-lg mt-0.5">
                    {kb.emoji}
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="font-medium text-gray-900 truncate">
                      {kb.name}
                    </div>
                    <div className="mt-1 text-xs text-indigo-500">
                      {group.publicResource ? (kb.readOnly ? "公开资料 · 只读" : "公开资料 · 可管理") : "私有 · 仅当前账号"}
                    </div>
                    {kb.description && (
                      <div className="text-xs text-gray-500 mt-1 line-clamp-2">
                        {kb.description}
                      </div>
                    )}
                  </div>
                </div>
              </button>
            ))}
            </section>)}
          </div>
        )}
      </div>
    </div>
  );
};

export default KnowledgeBaseTabContent;
