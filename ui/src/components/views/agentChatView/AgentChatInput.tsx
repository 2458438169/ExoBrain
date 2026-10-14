import React from "react";
import { Sender } from "@ant-design/x";

interface AgentChatInputProps {
  value: string;
  onChange: (message: string) => void;
  onSend: (message: string) => Promise<boolean>;
  disabled?: boolean;
  loading?: boolean;
}

const AgentChatInput: React.FC<AgentChatInputProps> = ({ value, onChange, onSend, disabled, loading }) => {
  return (
    <Sender
      onSubmit={async () => {
        if (await onSend(value.trim())) onChange("");
      }}
      placeholder={loading ? "正在生成回答，请稍候…" : "输入消息…"}
      value={value}
      onChange={onChange}
      disabled={disabled}
      loading={loading}
    />
  );
};

export default AgentChatInput;
