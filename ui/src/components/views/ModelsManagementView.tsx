import { useCallback, useEffect, useState } from "react";
import { Alert, Button, Checkbox, Form, Input, InputNumber, Modal, Popconfirm, Radio, Select, Space, Switch, Table, Tag, Typography } from "antd";
import type { TableColumnsType } from "antd";
import { DeleteOutlined, EditOutlined, ExperimentOutlined, PlusOutlined, ReloadOutlined, SwapOutlined } from "@ant-design/icons";
import { createAdminModel, deleteAdminModel, getAdminModels, getModelBindings, rebindKnowledgeBases, testAdminModel, updateAdminModel, type AdminModel, type KnowledgeBaseBinding, type ModelKind, type ModelProtocol, type ModelRequest, type ModelTestResult } from "../../api/models.ts";
import { notify } from "../../api/feedback.ts";

const errorText = (error: unknown) => error instanceof Error ? error.message : "操作失败，请稍后重试";
const protocolLabels: Record<ModelProtocol, string> = { openai: "OpenAI 兼容", deepseek: "DeepSeek", zhipuai: "智谱 AI", ollama: "Ollama" };

/**
 * 迁移已有知识库到另一个向量模型。
 *
 * 只改绑定关系、不重建向量，因此必须先确认新旧模型是同一向量空间（同一模型改名/别名）。
 * 换模型或供应商时旧向量与新查询向量不可比，必须重新导入资料——后端也会拒绝没有确认的请求。
 */
function KnowledgeBaseMigration({ model, models, onClose, onDone }: { model: AdminModel; models: AdminModel[]; onClose: () => void; onDone: () => Promise<void> }) {
  const [bindings, setBindings] = useState<KnowledgeBaseBinding[]>([]);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<string[]>([]);
  const [confirmed, setConfirmed] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const modelName = useCallback((id: string) => models.find((item) => item.id === id)?.name ?? id, [models]);
  useEffect(() => {
    let active = true;
    void getModelBindings().then((response) => {
      if (!active) return;
      const pending = response.bindings.filter((binding) => binding.modelId !== model.id);
      setBindings(pending);
      setSelected(pending.map((binding) => binding.knowledgeBaseId));
    }).catch((failure) => { if (active) setError(errorText(failure)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [model.id]);
  const submit = async () => {
    setSaving(true);
    setError("");
    try {
      const result = await rebindKnowledgeBases(model.id, { vectorSpaceUnchanged: confirmed, knowledgeBaseIds: selected });
      notify.success(`已把 ${result.rebound} 个知识库迁移到 ${model.name}`);
      await onDone();
      onClose();
    } catch (failure) { setError(errorText(failure)); }
    finally { setSaving(false); }
  };
  return (
    <Modal open title={`把知识库迁移到 ${model.name}`} onCancel={onClose} okText="迁移" cancelText="取消" confirmLoading={saving} okButtonProps={{ disabled: !confirmed || selected.length === 0 }} onOk={() => void submit()}>
      <div className="space-y-3 text-sm">
        <Alert type="warning" showIcon title="迁移只改绑定关系，不会重建已有向量。" description="只有在目标模型与当前模型属于同一向量空间（同一模型改名或别名）时才可以迁移；换了模型或供应商必须重新导入资料。" />
        <div className="text-xs leading-6 text-slate-500">
          <div>目标模型：{model.name}（{model.model}）</div>
          <div>服务地址：{model.baseUrl}</div>
        </div>
        <div>
          <div className="mb-1 font-medium">当前绑定在其它模型上的知识库（{bindings.length}）</div>
          {loading ? <div className="text-xs text-slate-500">加载中…</div> : bindings.length === 0 ? <div className="text-xs text-slate-500">没有需要迁移的知识库。</div> : (
            <Checkbox.Group value={selected} onChange={(values) => setSelected(values as string[])} className="flex! flex-col! gap-1!">
              {bindings.map((binding) => (
                <Checkbox key={binding.knowledgeBaseId} value={binding.knowledgeBaseId}>
                  <span className="break-all">{binding.name}</span>
                  <span className="ml-2 text-xs text-slate-500">当前：{modelName(binding.modelId)}</span>
                </Checkbox>
              ))}
            </Checkbox.Group>
          )}
        </div>
        <Checkbox checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)}>我确认新旧模型属于同一向量空间（仅改名/别名），不需要重新导入资料</Checkbox>
        {error && <Alert type="error" showIcon title={error} />}
      </div>
    </Modal>
  );
}
function ModelEditor({ model, onClose, onSaved }: { model: AdminModel | null; onClose: () => void; onSaved: () => Promise<void> }) {
  const [form] = Form.useForm<ModelRequest>();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const kind = Form.useWatch("kind", form) ?? model?.kind ?? "LLM";
  const protocol = Form.useWatch("protocol", form) ?? model?.protocol ?? "openai";
  const clearApiKey = Form.useWatch("clearApiKey", form);
  const enabled = Form.useWatch("enabled", form);
  const protocols: ModelProtocol[] = kind === "EMBEDDING" ? ["openai", "ollama"] : ["openai", "deepseek", "zhipuai"];
  const save = async (values: ModelRequest) => {
    setSaving(true);
    setError("");
    try {
      const body: ModelRequest = {
        id: values.id.trim(), name: values.name.trim(), kind: values.kind, protocol: values.protocol,
        baseUrl: values.baseUrl.trim().replace(/\/+$/, ""), model: values.model.trim(),
        enabled: values.enabled, defaultModel: values.enabled && values.defaultModel,
        ...(values.kind === "EMBEDDING" ? { dimensions: values.dimensions ?? 1024 } : {}),
        ...(values.clearApiKey ? { clearApiKey: true } : values.apiKey?.trim() ? { apiKey: values.apiKey.trim() } : {}),
      };
      if (model) {
        const { id, ...updates } = body;
        await updateAdminModel(id, updates);
      } else await createAdminModel(body);
      notify.success(model ? "模型已更新" : "模型已添加");
      await onSaved();
      onClose();
    } catch (failure) { setError(errorText(failure)); }
    finally { setSaving(false); }
  };
  return (
    <Modal open title={model ? "编辑模型" : "添加系统模型"} width={660} onCancel={onClose} onOk={() => form.submit()} cancelText="取消" okText="保存" confirmLoading={saving} cancelButtonProps={{ disabled: saving }} closable={!saving} maskClosable={!saving} styles={{ body: { maxHeight: "70dvh", overflowY: "auto", paddingRight: 4 } }}>
      {error && <Alert type="error" showIcon title={error} className="mb-4!" />}
      <Form form={form} layout="vertical" initialValues={model ? { id: model.id, name: model.name, kind: model.kind, protocol: model.protocol, baseUrl: model.baseUrl, model: model.model, enabled: model.enabled, defaultModel: model.defaultModel, dimensions: model.dimensions, clearApiKey: false } : { kind: "LLM", protocol: "openai", enabled: false, defaultModel: false, dimensions: 1024 }} onFinish={save} requiredMark={false} onValuesChange={(changed) => {
        if (changed.kind) form.setFieldsValue({ protocol: "openai", dimensions: changed.kind === "EMBEDDING" ? 1024 : undefined });
        if (changed.enabled === false) form.setFieldsValue({ defaultModel: false });
      }}>
        <div className="grid gap-x-4 sm:grid-cols-2">
          <Form.Item name="name" label="显示名称" rules={[{ required: true, whitespace: true, message: "请输入显示名称" }]}><Input maxLength={128} placeholder="例如 DeepSeek Chat" /></Form.Item>
          <Form.Item name="id" label="模型 ID" extra={model ? "创建后不可修改。" : "使用小写字母、数字、点、短横线或下划线。"} rules={[{ required: true, message: "请输入模型 ID" }, { pattern: /^[a-z0-9][a-z0-9._-]{0,63}$/, message: "使用 1–64 位小写字母、数字、点、短横线或下划线" }]}><Input maxLength={64} disabled={!!model} placeholder="例如 deepseek-chat" /></Form.Item>
          <Form.Item name="kind" label="模型用途" rules={[{ required: true }]}><Select disabled={!!model} options={[{ value: "LLM", label: "LLM · 对话" }, { value: "EMBEDDING", label: "Embedding · 向量化" }]} /></Form.Item>
          <Form.Item name="protocol" label="接口协议" rules={[{ required: true }]}><Select options={protocols.map((value) => ({ value, label: protocolLabels[value] }))} /></Form.Item>
        </div>
        <Form.Item name="baseUrl" label="服务地址" extra={protocol === "openai" ? "填写包含 /v1 的 API 根地址。" : protocol === "zhipuai" ? "填写包含 /api/paas/v4 的 API 根地址。" : "填写模型服务的 API 根地址。"} rules={[{ required: true, whitespace: true, message: "请输入服务地址" }, { type: "url", message: "请输入有效的 HTTP 或 HTTPS 地址" }]}><Input placeholder={protocol === "ollama" ? "http://localhost:11434" : "https://api.example.com/v1"} maxLength={1000} /></Form.Item>
        <div className="grid gap-x-4 sm:grid-cols-2">
          <Form.Item name="model" label="服务中的模型名" rules={[{ required: true, whitespace: true, message: "请输入模型名" }]}><Input maxLength={128} placeholder={kind === "EMBEDDING" ? "例如 bge-m3" : "例如 deepseek-chat"} /></Form.Item>
          {kind === "EMBEDDING" && <Form.Item name="dimensions" label="向量维度" extra="当前知识索引使用 1024 维。" rules={[{ required: true }]}><InputNumber min={1024} max={1024} disabled className="w-full!" /></Form.Item>}
        </div>
        <Form.Item name="apiKey" label="API Key" extra={model?.apiKeyConfigured ? "已配置。留空保留现有密钥，填写新值即可替换。" : protocol === "ollama" ? "Ollama 未开启鉴权时可留空。" : "填写服务提供方的 API Key。"}>
          <Input.Password autoComplete="new-password" disabled={!!clearApiKey} placeholder={model?.apiKeyConfigured ? "已配置，留空保留" : "输入 API Key"} />
        </Form.Item>
        {model?.apiKeyConfigured && <Form.Item name="clearApiKey" valuePropName="checked"><Checkbox>清除已保存的 API Key</Checkbox></Form.Item>}
        <div className="flex flex-wrap gap-x-8">
          <Form.Item name="enabled" label="模型状态" valuePropName="checked"><Switch checkedChildren="启用" unCheckedChildren="停用" /></Form.Item>
          <Form.Item name="defaultModel" label="设为默认" valuePropName="checked"><Switch disabled={!enabled} checkedChildren="是" unCheckedChildren="否" /></Form.Item>
        </div>
        {kind === "EMBEDDING" && <div className="mb-2 space-y-1 text-xs leading-6 text-slate-500"><p>默认 Embedding 用于新知识库。已有知识库继续使用原模型。</p><p>已有知识库绑定此模型时，更换服务地址须保持相同模型与向量空间。更换供应商或模型时，请新建模型和知识库后重新导入资料。</p></div>}
      </Form>
    </Modal>
  );
}

export default function ModelsManagementView() {
  const [models, setModels] = useState<AdminModel[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [kind, setKind] = useState<ModelKind>("LLM");
  const [editor, setEditor] = useState<{ model: AdminModel | null } | null>(null);
  const [testing, setTesting] = useState<string | null>(null);
  const [migration, setMigration] = useState<AdminModel | null>(null);
  const [testResult, setTestResult] = useState<{ name: string; result: ModelTestResult } | null>(null);
  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try { setModels((await getAdminModels()).models); }
    catch (failure) { setError(errorText(failure)); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);
  const remove = async (model: AdminModel) => {
    setError("");
    try { await deleteAdminModel(model.id); notify.success("模型已删除"); await load(); }
    catch (failure) { setError(errorText(failure)); }
  };
  const test = async (model: AdminModel) => {
    setTesting(model.id);
    setTestResult(null);
    setError("");
    try { setTestResult({ name: model.name, result: await testAdminModel(model.id) }); }
    catch (failure) { setError(errorText(failure)); }
    finally { setTesting(null); }
  };
  const openMigration = (model: AdminModel) => {
    setError("");
    setMigration(model);
  };
  const columns: TableColumnsType<AdminModel> = [
    { title: "模型", key: "name", render: (_, model) => <div className="min-w-0"><div className="font-medium text-slate-900">{model.name}{model.defaultModel && <Tag color="geekblue" className="ml-2!">默认</Tag>}</div><div className="mt-1 break-all text-xs text-slate-500">{model.model}</div></div> },
    { title: "接口", dataIndex: "protocol", width: 125, responsive: ["md"], render: (protocol: ModelProtocol) => protocolLabels[protocol] || protocol },
    { title: "状态", key: "enabled", width: 90, render: (_, model) => <Tag color={model.enabled ? "success" : "default"}>{model.enabled ? "启用" : "停用"}</Tag> },
    { title: "密钥", key: "apiKeyConfigured", width: 105, responsive: ["lg"], render: (_, model) => <span className="text-xs text-slate-500">{model.apiKeyConfigured ? "已配置" : model.protocol === "ollama" ? "未配置（可选）" : "未配置"}</span> },
    { title: "操作", key: "action", width: 280, render: (_, model) => <Space size={0} wrap><Button type="link" size="small" icon={<ExperimentOutlined />} loading={testing === model.id} disabled={testing !== null && testing !== model.id} onClick={() => void test(model)} aria-label={`测试模型 ${model.name}`}>测试</Button>{model.kind === "EMBEDDING" && <Button type="link" size="small" icon={<SwapOutlined />} disabled={!model.enabled} onClick={() => openMigration(model)} aria-label={`迁移知识库到 ${model.name}`}>迁移知识库</Button>}<Button type="link" size="small" icon={<EditOutlined />} onClick={() => setEditor({ model })} aria-label={`编辑模型 ${model.name}`}>编辑</Button><Popconfirm title="删除这个模型？" description="正在使用的模型需解除关联后才能删除。" onConfirm={() => remove(model)} okText="删除" cancelText="取消" okButtonProps={{ danger: true }}><Button type="text" danger size="small" icon={<DeleteOutlined />} aria-label={`删除模型 ${model.name}`}>删除</Button></Popconfirm></Space> },
  ];
  return (
    <section className="h-full min-w-0 overflow-y-auto bg-slate-50/60 p-5 sm:p-8" aria-label="系统模型管理">
      <div className="mx-auto max-w-6xl">
        <div className="mb-6 flex flex-wrap items-start justify-between gap-4"><div><Typography.Title level={2} className="mb-2!">系统模型</Typography.Title><p className="text-sm leading-6 text-slate-500">管理供用户选择的对话模型和知识库使用的 Embedding 模型。</p></div><Space wrap><Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>刷新</Button><Button type="primary" icon={<PlusOutlined />} onClick={() => setEditor({ model: null })}>添加模型</Button></Space></div>
        <Radio.Group value={kind} onChange={(event) => setKind(event.target.value)} optionType="button" buttonStyle="solid" options={[{ value: "LLM", label: "LLM 对话模型" }, { value: "EMBEDDING", label: "Embedding 模型" }]} className="mb-5!" />
        {kind === "EMBEDDING" && <Alert type="info" showIcon title="默认 Embedding 用于新知识库，已有知识库继续使用原模型。" className="mb-4!" />}
        {error && <Alert type="error" showIcon title={error} className="mb-4!" />}
        {testResult && <Alert type={testResult.result.success ? "success" : "error"} showIcon title={`${testResult.name}：${testResult.result.message}`} description={<span>{testResult.result.latencyMs !== undefined && `耗时 ${testResult.result.latencyMs} ms`}{testResult.result.dimensions !== undefined && ` · ${testResult.result.dimensions} 维`}</span>} closable onClose={() => setTestResult(null)} className="mb-4!" />}
        <div className="overflow-hidden rounded-xl border border-slate-200 bg-white"><Table rowKey="id" columns={columns} dataSource={models.filter((model) => model.kind === kind)} loading={loading} scroll={{ x: 500 }} pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 个模型` }} locale={{ emptyText: "暂无模型，点击添加模型开始配置" }} /></div>
        {editor && <ModelEditor model={editor.model} onClose={() => setEditor(null)} onSaved={load} />}
        {migration && <KnowledgeBaseMigration model={migration} models={models} onClose={() => setMigration(null)} onDone={load} />}
      </div>
    </section>
  );
}
