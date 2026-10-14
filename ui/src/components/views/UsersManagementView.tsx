import { useCallback, useEffect, useState } from "react";
import { Alert, Button, Form, Input, Modal, Select, Space, Switch, Table, Tag, Typography } from "antd";
import type { TableColumnsType } from "antd";
import { EditOutlined, PlusOutlined, ReloadOutlined } from "@ant-design/icons";
import { createAccountUser, getAccountUsers, updateAccountUser, type AccountUser, type UserRequest } from "../../api/accounts.ts";
import { useAccount } from "../../contexts/accountState.ts";
import { notify } from "../../api/feedback.ts";

const errorText = (error: unknown) => error instanceof Error ? error.message : "操作失败，请稍后重试";

function UserEditor({ user, onClose, onSaved }: { user: AccountUser | null; onClose: () => void; onSaved: (user: AccountUser) => Promise<void> }) {
  const [form] = Form.useForm<UserRequest>();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const save = async (values: UserRequest) => {
    setSaving(true);
    setError("");
    try {
      const body = {
        displayName: values.displayName.trim(), role: values.role, enabled: values.enabled,
        ...(values.password ? { password: values.password } : {}),
      };
      const result = user
        ? await updateAccountUser(user.id, body)
        : await createAccountUser({ ...values, ...body, username: values.username.trim() });
      notify.success(user ? "账号已更新" : "账号已创建");
      await onSaved(result.user);
      onClose();
    } catch (failure) { setError(errorText(failure)); }
    finally { setSaving(false); }
  };
  return (
    <Modal open title={user ? "编辑账号" : "新增账号"} onCancel={onClose} onOk={() => form.submit()} cancelText="取消" okText="保存" confirmLoading={saving} cancelButtonProps={{ disabled: saving }} closable={!saving} maskClosable={!saving} width={520}>
      {error && <Alert type="error" showIcon title={error} className="mb-4!" />}
      <Form form={form} layout="vertical" initialValues={user ? { username: user.username, displayName: user.displayName, role: user.role, enabled: user.enabled } : { role: "USER", enabled: true }} onFinish={save} requiredMark={false}>
        <Form.Item label="账号" name="username" rules={[{ required: true, message: "请输入账号" }, { pattern: /^[a-zA-Z0-9._-]{2,32}$/, message: "使用 2–32 位字母、数字、点、下划线或短横线" }]}>
          <Input autoComplete="off" maxLength={32} disabled={!!user} placeholder="例如 alex" />
        </Form.Item>
        <Form.Item label="显示名称" name="displayName" rules={[{ required: true, whitespace: true, message: "请输入显示名称" }, { max: 64, message: "最多 64 个字符" }]}>
          <Input maxLength={64} placeholder="显示在工作区中的名称" />
        </Form.Item>
        <Form.Item label={user ? "重置密码" : "密码"} name="password" extra={user ? "留空保留当前密码。重置后，此账号需重新登录。" : "至少 6 个字符。"} rules={[{ required: !user, message: "请输入密码" }, { min: 6, max: 128, message: "密码应为 6–128 个字符" }]}>
          <Input.Password autoComplete="new-password" maxLength={128} placeholder={user ? "填写新密码" : "设置登录密码"} />
        </Form.Item>
        <Form.Item label="角色" name="role" rules={[{ required: true }]}>
          <Select options={[{ value: "USER", label: "用户" }, { value: "ADMIN", label: "管理员" }]} />
        </Form.Item>
        <p className="-mt-2 mb-5 text-xs leading-6 text-slate-500">用户使用自己的助手、知识库与聊天记录；管理员还可管理账号和系统模型。</p>
        <Form.Item label="账号状态" name="enabled" valuePropName="checked">
          <Switch checkedChildren="启用" unCheckedChildren="停用" />
        </Form.Item>
      </Form>
    </Modal>
  );
}

export default function UsersManagementView() {
  const account = useAccount();
  const [users, setUsers] = useState<AccountUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [editor, setEditor] = useState<{ user: AccountUser | null } | null>(null);
  const [updating, setUpdating] = useState<string | null>(null);
  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try { setUsers((await getAccountUsers()).users); }
    catch (failure) { setError(errorText(failure)); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);
  const saved = async (user: AccountUser) => {
    setUsers((current) => current.some((item) => item.id === user.id) ? current.map((item) => item.id === user.id ? user : item) : [...current, user]);
    if (user.id === account?.user?.id) await account.refresh();
  };
  const toggleEnabled = async (user: AccountUser, enabled: boolean) => {
    setUpdating(user.id);
    setError("");
    try {
      const result = await updateAccountUser(user.id, { enabled });
      await saved(result.user);
      notify.success(enabled ? "账号已启用" : "账号已停用");
    } catch (failure) { setError(errorText(failure)); }
    finally { setUpdating(null); }
  };
  const columns: TableColumnsType<AccountUser> = [
    { title: "账号", key: "username", render: (_, user) => <div><span className="font-medium text-slate-900">{user.username}</span>{account?.user?.id === user.id && <Tag className="ml-2!">我</Tag>}<div className="mt-1 text-xs text-slate-500">{user.displayName}</div></div> },
    { title: "角色", dataIndex: "role", width: 100, render: (role: AccountUser["role"]) => <Tag color={role === "ADMIN" ? "purple" : "blue"}>{role === "ADMIN" ? "管理员" : "用户"}</Tag> },
    { title: "状态", key: "enabled", width: 100, render: (_, user) => <Switch aria-label={`${user.username} 账号状态`} checkedChildren="启用" unCheckedChildren="停用" checked={user.enabled} loading={updating === user.id} disabled={!!updating} onChange={(enabled) => void toggleEnabled(user, enabled)} /> },
    { title: "创建时间", dataIndex: "createdAt", responsive: ["lg"], width: 175, render: (value?: string) => value ? value.replace("T", " ").slice(0, 16) : "—" },
    { title: "操作", key: "action", width: 90, render: (_, user) => <Button type="link" size="small" icon={<EditOutlined />} aria-label={`编辑账号 ${user.username}`} onClick={() => setEditor({ user })}>编辑</Button> },
  ];
  return (
    <section className="h-full min-w-0 overflow-y-auto bg-slate-50/60 p-5 sm:p-8" aria-label="用户管理">
      <div className="mx-auto max-w-6xl">
        <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
          <div><Typography.Title level={2} className="mb-2!">用户管理</Typography.Title><p className="text-sm leading-6 text-slate-500">创建账号，调整角色、登录状态和密码。</p></div>
          <Space wrap><Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>刷新</Button><Button type="primary" icon={<PlusOutlined />} onClick={() => setEditor({ user: null })}>新增账号</Button></Space>
        </div>
        {error && <Alert type="error" showIcon title={error} className="mb-4!" />}
        <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
          <Table rowKey="id" columns={columns} dataSource={users} loading={loading} pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 个账号` }} scroll={{ x: 440 }} />
        </div>
        {editor && <UserEditor user={editor.user} onClose={() => setEditor(null)} onSaved={saved} />}
      </div>
    </section>
  );
}
