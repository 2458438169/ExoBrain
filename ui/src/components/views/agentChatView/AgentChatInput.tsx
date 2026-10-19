import React from "react";
import { Sender } from "@ant-design/x";

interface AgentChatInputProps {
  value: string;
  onChange: (message: string) => void;
  /** 返回 true 表示服务端已确认受理，这时才清空草稿。 */
  onSend: (message: string) => Promise<boolean>;
  /** 本轮正在执行：输入框仍可编辑下一条草稿，但发送按钮变成停止按钮。 */
  waiting?: boolean;
  /** 停止按钮：只有拿到 runId 时才会真正调用取消接口。 */
  onCancel?: () => void;
  /** 是否已经有可取消的 runId；没有时提示「正在提交」，不假装已经停止。 */
  cancellable?: boolean;
  /** 取消请求已登记、终态尚未确认。 */
  cancelling?: boolean;
  /** 连接是否可用；断开时说明正在恢复。 */
  connected?: boolean;
  /** 会话没有可用的 Agent 等硬性禁用条件。 */
  disabled?: boolean;
}

/**
 * 输入区把三种能力分开：可编辑草稿 / 可发送 / 可取消。
 * - 等待期间仍能写下一句（草稿不会被清空或锁定）；
 * - 发送按钮只在有内容且连接可用时提交，提交过程由发送协调器去重；
 * - 等待时 Sender 的发送按钮变为停止按钮，调用真实取消接口。
 */
const AgentChatInput: React.FC<AgentChatInputProps> = ({
  value,
  onChange,
  onSend,
  waiting = false,
  onCancel,
  cancellable = false,
  cancelling = false,
  connected = true,
  disabled = false,
}) => {
  const canSend = !!value.trim() && !waiting && connected && !disabled;
  const hint = waiting
    ? cancelling
      ? "正在取消：停止请求已登记，终态以运行状态为准"
      : cancellable
        ? "正在执行 · 可以随时停止"
        : "正在提交问题…"
    : !connected
      ? "连接已断开，正在恢复；已受理的问题不会重复发送"
      : "Enter 发送 · Shift + Enter 换行";

  return (
    <div className="min-w-0">
      <Sender
        value={value}
        onChange={onChange}
        onSubmit={async () => {
          if (!canSend) return;
          if (await onSend(value.trim())) onChange("");
        }}
        loading={waiting}
        onCancel={waiting ? onCancel : undefined}
        disabled={disabled}
        autoSize={{ minRows: 1, maxRows: 6 }}
        placeholder={
          waiting
            ? "可以先写下一条问题，本轮结束后再发送"
            : connected
              ? "输入消息…"
              : "连接恢复后可以继续提问"
        }
      />
      <p className="mt-1 text-xs text-slate-400" role="status">
        {hint}
      </p>
    </div>
  );
};

export default AgentChatInput;
