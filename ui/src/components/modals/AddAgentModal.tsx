import { useEffect, useState } from "react";
import { Alert, Button, Checkbox, Form, Input, Modal, Select, Slider, Spin } from "antd";
import { SaveOutlined } from "@ant-design/icons";
import { getOptionalTools, type AgentVO, type CreateAgentRequest, type ToolVO, type UpdateAgentRequest } from "../../api/api.ts";
import { getAvailableModels, type AvailableModel } from "../../api/models.ts";
import { useKnowledgeBases } from "../../hooks/useKnowledgeBases.ts";

interface AddAgentModalProps {
  open: boolean;
  onClose: () => void;
  createAgentHandle: (request: CreateAgentRequest) => Promise<void>;
  updateAgentHandle?: (agentId: string, request: UpdateAgentRequest) => Promise<void>;
  editingAgent?: AgentVO | null;
}

const sections = [
  { key: "base", label: "基础设置" },
  { key: "model", label: "模型设置" },
  { key: "knowledge", label: "知识库" },
  { key: "tools", label: "工具调用" },
];

function AgentEditor({ onClose, createAgentHandle, updateAgentHandle, editingAgent }: Omit<AddAgentModalProps, "open">) {
  const [form] = Form.useForm<CreateAgentRequest>();
  const { knowledgeBases, loading: loadingKnowledgeBases } = useKnowledgeBases();
  const [selectedKey, setSelectedKey] = useState("base");
  const [models, setModels] = useState<AvailableModel[]>([]);
  const [tools, setTools] = useState<ToolVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let active = true;
    void Promise.all([getAvailableModels(), getOptionalTools()]).then(([catalog, toolList]) => {
      if (!active) return;
      const available = catalog.models.filter((model) => model.kind === "LLM" && model.enabled);
      setModels(available);
      setTools(toolList.tools);
      setLoaded(true);
      if (!editingAgent && !form.getFieldValue("model")) {
        form.setFieldValue("model", (available.find((model) => model.defaultModel) ?? available[0])?.id);
      }
    }).catch((failure: unknown) => {
      if (active) setError(failure instanceof Error ? failure.message : "模型与工具加载失败，请重试");
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [editingAgent, form, retry]);

  const save = async (values: CreateAgentRequest) => {
    if (!models.some((model) => model.id === values.model)) {
      setError("请选择一个已启用的对话模型");
      setSelectedKey("model");
      return;
    }
    setSaving(true);
    setError("");
    try {
      const allowed = new Set(tools.map((tool) => tool.name));
      const body = { ...values, name: values.name.trim(), allowedTools: (values.allowedTools ?? []).filter((name) => allowed.has(name)) };
      if (editingAgent && updateAgentHandle) await updateAgentHandle(editingAgent.id, body);
      else await createAgentHandle(body);
      onClose();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "保存失败，请重试");
    } finally { setSaving(false); }
  };

  const currentUnavailable = editingAgent && !models.some((model) => model.id === editingAgent.model);
  return (
    <Modal open onCancel={onClose} title={editingAgent ? "编辑智能体" : "新建智能体助手"} width={800} centered closable={!saving} maskClosable={!saving} footer={<div className="flex justify-end gap-2"><Button onClick={onClose} disabled={saving}>取消</Button><Button type="primary" icon={<SaveOutlined />} loading={saving} disabled={loading || !loaded || models.length === 0} onClick={() => form.submit()}>{editingAgent ? "更新" : "保存"}</Button></div>}>
      <div className="mb-4 flex gap-1 overflow-x-auto rounded-xl bg-slate-50 p-1" role="tablist" aria-label="助手设置">
        {sections.map((section) => <button key={section.key} type="button" role="tab" aria-selected={selectedKey === section.key} className={`min-w-20 flex-1 cursor-pointer whitespace-nowrap rounded-lg px-2 py-2 text-sm ${selectedKey === section.key ? "bg-white font-medium text-indigo-600 shadow-sm" : "text-slate-500 hover:text-slate-900"}`} onClick={() => setSelectedKey(section.key)}>{section.label}</button>)}
      </div>
      {error && <Alert type="error" showIcon title={error} className="mb-4!" action={!loaded ? <Button size="small" onClick={() => { setLoading(true); setError(""); setRetry((value) => value + 1); }}>重试</Button> : undefined} />}
      {!loading && loaded && models.length === 0 && <Alert type="warning" showIcon title="暂无已启用的对话模型，请联系管理员配置。" className="mb-4!" />}
      <Form form={form} layout="vertical" requiredMark={false} initialValues={editingAgent ? { name: editingAgent.name, description: editingAgent.description || "", systemPrompt: editingAgent.systemPrompt || "", model: editingAgent.model, allowedTools: editingAgent.allowedTools || [], allowedKbs: editingAgent.allowedKbs || [], chatOptions: editingAgent.chatOptions || { temperature: 0.7, topP: 1, messageLength: 20 } } : { name: "", description: "", systemPrompt: "", allowedTools: [], allowedKbs: [], chatOptions: { temperature: 0.7, topP: 1, messageLength: 20 } }} onFinish={save} onFinishFailed={({ errorFields }) => { const field = errorFields[0]?.name[0]; setSelectedKey(field === "model" ? "model" : "base"); }}>
        <div className="max-h-[55dvh] min-h-72 overflow-y-auto px-1 pb-1" role="tabpanel">
          <div hidden={selectedKey !== "base"}>
            <Form.Item name="name" label="名称" rules={[{ required: true, whitespace: true, message: "请输入助手名称" }]}><Input placeholder="例如 技术助手" maxLength={100} /></Form.Item>
            <Form.Item name="description" label="描述"><Input.TextArea placeholder="介绍这个助手擅长什么" rows={2} maxLength={1000} /></Form.Item>
            <Form.Item name="systemPrompt" label="提示词"><Input.TextArea placeholder="描述助手的职责、回答方式和工作范围" rows={7} /></Form.Item>
          </div>
          <div hidden={selectedKey !== "model"}>
            <Form.Item name="model" label="对话模型" rules={[{ required: true, message: "请选择对话模型" }]}>
              <Select loading={loading} placeholder="选择系统提供的模型" options={[
                ...models.map((model) => ({ value: model.id, label: `${model.name}${model.defaultModel ? "（默认）" : ""}` })),
                ...(currentUnavailable ? [{ value: editingAgent.model, label: `${editingAgent.model}（不可用，请重新选择）`, disabled: true }] : []),
              ]} />
            </Form.Item>
            <Form.Item name={["chatOptions", "temperature"]} label="温度" extra="较低的值更稳定，较高的值更有变化。"><Slider min={0} max={2} step={0.1} marks={{ 0: "0", 1: "1", 2: "2" }} /></Form.Item>
            <Form.Item name={["chatOptions", "topP"]} label="Top P"><Slider min={0.1} max={1} step={0.1} marks={{ 0.1: "0.1", 1: "1" }} /></Form.Item>
            <Form.Item name={["chatOptions", "messageLength"]} label="上下文消息数"><Slider min={2} max={50} step={1} marks={{ 2: "2", 20: "20", 50: "50" }} /></Form.Item>
          </div>
          <div hidden={selectedKey !== "knowledge"}>
            <p className="mb-4 text-sm leading-6 text-slate-500">选择助手可检索的知识库，支持自己的资料和公开资料。</p>
            {loadingKnowledgeBases ? <Spin /> : knowledgeBases.length === 0 ? <p className="py-10 text-center text-slate-400">暂无知识库，可先创建一个知识库。</p> : <Form.Item name="allowedKbs"><Checkbox.Group className="w-full!" options={knowledgeBases.map((kb) => ({ value: kb.knowledgeBaseId, label: <span className="block py-2"><span className="font-medium">{kb.name}</span>{kb.readOnly && <span className="ml-2 text-xs text-indigo-500">公开资料</span>}{kb.description && <span className="mt-1 block text-xs text-slate-500">{kb.description}</span>}</span>, style: { display: "flex", width: "100%", marginBottom: 10 } }))} /></Form.Item>}
          </div>
          <div hidden={selectedKey !== "tools"}>
            <p className="mb-4 text-sm leading-6 text-slate-500">知识检索、日期查询等基础能力已提供。可在下方添加当前账号可用的工具。</p>
            {loading ? <Spin /> : tools.length === 0 ? <p className="py-10 text-center text-slate-400">当前没有可添加的扩展工具。</p> : <Form.Item name="allowedTools"><Checkbox.Group className="w-full!" options={tools.map((tool) => ({ value: tool.name, label: <span className="block py-2"><span className="font-medium">{tool.name}</span><span className="mt-1 block text-xs text-slate-500">{tool.description}</span></span>, style: { display: "flex", width: "100%", marginBottom: 10 } }))} /></Form.Item>}
          </div>
        </div>
      </Form>
    </Modal>
  );
}

export default function AddAgentModal({ open, ...props }: AddAgentModalProps) {
  return open && !props.editingAgent?.readOnly ? <AgentEditor {...props} key={props.editingAgent?.id ?? "new"} /> : null;
}
