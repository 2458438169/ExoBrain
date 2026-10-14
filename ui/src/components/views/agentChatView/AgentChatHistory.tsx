import React, { useState, useRef, useEffect, useCallback } from "react";
import { Bubble } from "@ant-design/x";
import XMarkdown from "@ant-design/x-markdown";
import {
  ToolOutlined,
  FileSearchOutlined,
  RobotOutlined,
} from "@ant-design/icons";
import type { ChatMessageVO, SseMessageType, ToolCall, ToolResponse } from "../../../types";

interface AgentChatHistoryProps {
  messages: ChatMessageVO[];
  autoScroll?: boolean;
  displayAgentStatus?: boolean;
  agentStatusText?: string;
  agentStatusType?: SseMessageType;
}

const TOOL_LABELS: Record<string, string> = {
  KnowledgeTool: "知识库检索",
  getDate: "日期查询",
  directAnswer: "生成回答",
  terminate: "结束任务",
};

function formatToolData(value: string | null | undefined): string {
  if (!value) return "无内容";
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}

const ToolCallDisplay: React.FC<{ toolCall: ToolCall }> = ({ toolCall }) => (
  <details className="w-full rounded-lg border border-indigo-100 bg-indigo-50/50 text-xs">
    <summary className="cursor-pointer px-3 py-2 leading-6 text-indigo-700">
      <ToolOutlined className="mx-1.5" />
      <span className="font-medium">工具调用 · {TOOL_LABELS[toolCall.name] || toolCall.name}</span>
      {TOOL_LABELS[toolCall.name] && <span className="ml-2 font-mono text-indigo-500">{toolCall.name}</span>}
    </summary>
    <div className="border-t border-indigo-100 px-3 py-2">
      <p className="mb-1 text-indigo-500">调用参数</p>
      <pre className="max-h-64 overflow-auto whitespace-pre-wrap break-words leading-6 text-slate-600">{formatToolData(toolCall.arguments)}</pre>
    </div>
  </details>
);

const ToolResponseDisplay: React.FC<{ toolResponse: ToolResponse }> = ({ toolResponse }) => (
  <details className="my-1.5 w-full rounded-lg border border-slate-200 bg-slate-50 text-xs">
    <summary className="cursor-pointer px-3 py-2 leading-6 text-slate-600">
      <FileSearchOutlined className="mx-1.5 text-teal-600" />
      <span className="font-medium">工具结果 · {TOOL_LABELS[toolResponse.name] || toolResponse.name}</span>
      {TOOL_LABELS[toolResponse.name] && <span className="ml-2 font-mono text-slate-400">{toolResponse.name}</span>}
    </summary>
    <div className="border-t border-slate-200 px-3 py-2">
      <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-words leading-6 text-slate-600">{formatToolData(toolResponse.responseData)}</pre>
    </div>
  </details>
);

const AgentChatHistory: React.FC<AgentChatHistoryProps> = ({
  messages,
  autoScroll = true,
  displayAgentStatus = false,
  agentStatusText = "",
  agentStatusType,
}) => {
  // 滚动容器引用
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  // 是否允许自动滚动（用户是否接近底部）
  const [isNearBottom, setIsNearBottom] = useState(true);
  // 容错阈值（像素）
  const SCROLL_THRESHOLD = 20;
  // 上一次消息数量，用于检测新消息
  const prevMessagesLengthRef = useRef(messages.length);

  // 检查是否接近底部
  const checkIfNearBottom = useCallback(() => {
    const container = scrollContainerRef.current;
    if (!container) return false;

    const { scrollTop, clientHeight, scrollHeight } = container;
    const distanceFromBottom = scrollHeight - scrollTop - clientHeight;
    return distanceFromBottom <= SCROLL_THRESHOLD;
  }, []);

  // 滚动到底部
  const scrollToBottom = useCallback(() => {
    const container = scrollContainerRef.current;
    if (!container) return;

    // 使用 requestAnimationFrame 确保 DOM 更新完成后再滚动
    requestAnimationFrame(() => {
      if (container) {
        container.scrollTop = container.scrollHeight;
      }
    });
  }, []);

  // 处理滚动事件，实时更新是否接近底部的状态
  const handleScroll = useCallback(() => {
    const nearBottom = checkIfNearBottom();
    setIsNearBottom(nearBottom);
  }, [checkIfNearBottom]);

  // 监听滚动事件
  useEffect(() => {
    const container = scrollContainerRef.current;
    if (!container) return;

    // 初始化时检查是否在底部（延迟执行以避免同步 setState）
    const initTimer = setTimeout(() => {
      setIsNearBottom(checkIfNearBottom());
    }, 0);

    container.addEventListener("scroll", handleScroll, { passive: true });

    return () => {
      clearTimeout(initTimer);
      container.removeEventListener("scroll", handleScroll);
    };
  }, [handleScroll, checkIfNearBottom]);

  // 监听消息变化，决定是否自动滚动
  useEffect(() => {
    const hasNewMessage = messages.length > prevMessagesLengthRef.current;
    prevMessagesLengthRef.current = messages.length;

    // 如果有新消息且用户接近底部，则自动滚动
    if (autoScroll && hasNewMessage && isNearBottom) {
      scrollToBottom();
    }
  }, [messages, isNearBottom, scrollToBottom, autoScroll]);

  // 当 displayAgentStatus 变化时，如果用户接近底部，也自动滚动
  useEffect(() => {
    if (autoScroll && displayAgentStatus && isNearBottom) {
      scrollToBottom();
    }
  }, [displayAgentStatus, isNearBottom, scrollToBottom, autoScroll]);

  // 获取状态标签
  const getStatusLabel = () => {
    switch (agentStatusType) {
      case "AI_PLANNING":
        return "规划中";
      case "AI_THINKING":
        return "思考中";
      case "AI_EXECUTING":
        return "执行中";
      default:
        return "处理中";
    }
  };

  return (
    <div 
      ref={scrollContainerRef}
      className="min-h-0 flex-1 overflow-y-auto px-5 py-5 sm:px-8 lg:px-12"
    >
      {messages.map((message) => {
        return (
          <div className="mb-4" key={message.id}>
            {/* Assistant 消息 */}
            {message.role === "assistant" && (
              <Bubble
                content={
                  <div className="w-full">
                    {/* 工具调用展示 */}
                    {message.metadata?.toolCalls &&
                      message.metadata.toolCalls.length > 0 && (
                        <div className="mb-2 flex flex-wrap gap-2">
                          {message.metadata.toolCalls.map((toolCall) => (
                            <ToolCallDisplay key={toolCall.id} toolCall={toolCall} />
                          ))}
                        </div>
                      )}
                    {/* 消息内容 */}
                    {message.content && (
                      <div>
                        <XMarkdown
                          streaming={{ enableAnimation: false, hasNextChunk: false }}
                        >
                          {message.content}
                        </XMarkdown>
                      </div>
                    )}
                  </div>
                }
                placement="start"
              />
            )}

            {/* Tool 消息 - 简洁展示，不使用气泡 */}
            {message.role === "tool" && message.metadata?.toolResponse && (
              <div className="flex justify-start">
                <div className="w-full max-w-3xl">
                  <ToolResponseDisplay toolResponse={message.metadata.toolResponse} />
                </div>
              </div>
            )}

            {/* User 消息 */}
            {message.role === "user" && (
              <Bubble content={message.content} placement="end" />
            )}

            {/* System 消息 */}
            {message.role === "system" && (
              <div className="flex justify-center">
                <div className="px-3 py-1 bg-gray-100 text-gray-600 text-xs rounded-full flex items-center gap-1">
                  <RobotOutlined />
                  <span>{message.content}</span>
                </div>
              </div>
            )}
          </div>
        );
      })}
      {displayAgentStatus && (
        <div className="mb-3">
          <div
            className="animate-pulse"
            style={{
              animation: "pulse 0.8s cubic-bezier(0.4, 0, 0.6, 1) infinite",
              filter: "brightness(1.15)",
            }}
          >
            <Bubble
              content={
                <span className="flex items-center gap-2">
                  <span
                    className="font-semibold text-blue-600"
                    style={{
                      animation:
                        "pulse 0.7s cubic-bezier(0.4, 0, 0.6, 1) infinite",
                      textShadow:
                        "0 0 10px rgba(37, 99, 235, 1), 0 0 20px rgba(37, 99, 235, 0.8), 0 0 30px rgba(37, 99, 235, 0.5)",
                      filter: "brightness(1.3)",
                    }}
                  >
                    ✨ {getStatusLabel()}
                  </span>
                  <span className="text-gray-400">·</span>
                  <span className="text-gray-600">{agentStatusText}</span>
                </span>
              }
              placement="start"
            />
          </div>
        </div>
      )}
    </div>
  );
};

export default AgentChatHistory;
