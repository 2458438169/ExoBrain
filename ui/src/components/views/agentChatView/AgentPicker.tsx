import { Select } from "antd";
import { DownOutlined } from "@ant-design/icons";
import { getAgentEmoji } from "../../../utils";
import type { AgentVO } from "../../../api/api.ts";

interface AgentPickerProps {
  agents: AgentVO[];
  value: string;
  loading: boolean;
  onChange: (agentId: string) => void;
}

/**
 * 账号工作区的助手选择器。
 *
 * <p>公开体验只有一个固定助手，用一行文字说明即可；把 Select 放到单独的 chunk，
 * 游客首屏就不必为它下载 rc-select（UX-07 的首屏预算）。</p>
 */
export default function AgentPicker({ agents, value, loading, onChange }: AgentPickerProps) {
  return (
    <Select
      aria-label="选择智能体助手"
      value={value}
      onChange={onChange}
      loading={loading}
      disabled={!agents.length}
      variant="borderless"
      popupMatchSelectWidth={false}
      style={{ maxWidth: "100%" }}
      suffixIcon={<DownOutlined className="text-slate-400" />}
      placeholder={loading ? "正在连接助手…" : "暂无可用助手"}
      options={agents.map((agent) => ({ value: agent.id, label: `${getAgentEmoji(agent.id)} ${agent.name}` }))}
    />
  );
}
