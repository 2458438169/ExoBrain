import { useState } from "react";
import { Alert, Button, Dropdown, Form, Input, Modal, Tag } from "antd";
import { DownOutlined, LockOutlined, LogoutOutlined, UserOutlined } from "@ant-design/icons";
import { changePassword } from "../api/accounts.ts";
import { showRequestError } from "../api/http.ts";
import { useAccount } from "../contexts/accountState.ts";
import { notify } from "../api/feedback.ts";

function PasswordDialog({ onClose }: { onClose: () => void }) {
  const account = useAccount();
  const [form] = Form.useForm();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const submit = async ({ currentPassword, newPassword }: { currentPassword: string; newPassword: string }) => {
    setBusy(true);
    setError("");
    try {
      await changePassword(currentPassword, newPassword);
      notify.success("密码已更新，请重新登录");
      account?.clear();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "密码修改失败，请重试");
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal open title="修改密码" onCancel={onClose} cancelText="取消" okText="保存并重新登录" onOk={() => form.submit()} confirmLoading={busy} cancelButtonProps={{ disabled: busy }} closable={!busy} maskClosable={!busy}>
      <p className="mb-4 text-sm leading-6 text-slate-500">更新密码后，你的账号将在所有设备退出登录。</p>
      {error && <Alert type="error" showIcon title={error} className="mb-4!" />}
      <Form form={form} layout="vertical" onFinish={submit} requiredMark={false}>
        <Form.Item name="currentPassword" label="当前密码" rules={[{ required: true, message: "请输入当前密码" }]}>
          <Input.Password autoComplete="current-password" maxLength={128} />
        </Form.Item>
        <Form.Item name="newPassword" label="新密码" rules={[{ required: true, message: "请输入新密码" }, { min: 6, max: 128, message: "密码应为 6–128 个字符" }]}>
          <Input.Password autoComplete="new-password" maxLength={128} />
        </Form.Item>
        <Form.Item name="confirmation" label="确认新密码" dependencies={["newPassword"]} rules={[{ required: true, message: "请再次输入新密码" }, ({ getFieldValue }) => ({ validator: (_, value) => !value || getFieldValue("newPassword") === value ? Promise.resolve() : Promise.reject(new Error("两次输入的密码不一致")) })]}>
          <Input.Password autoComplete="new-password" maxLength={128} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

export default function AccountMenu() {
  const account = useAccount();
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);
  if (!account?.user) return null;
  const user = account.user;
  const logout = async () => {
    setLoggingOut(true);
    try { await account.logout(); }
    catch (error) { showRequestError(error, "退出失败，请重试"); }
    finally { setLoggingOut(false); }
  };
  return (
    <div className="shrink-0 border-t-2 border-dashed border-sketch py-3">
      <Dropdown trigger={["click"]} placement="topLeft" menu={{ items: [
        { key: "password", label: "修改密码", icon: <LockOutlined />, onClick: () => setPasswordOpen(true) },
        { key: "guest", label: <a href="/">游客免登录体验</a> },
        { type: "divider" },
        { key: "logout", label: "退出登录", icon: <LogoutOutlined />, onClick: () => void logout(), disabled: loggingOut },
      ] }}>
        <Button type="text" block className="h-auto! px-2! py-2!" loading={loggingOut} aria-label="账号菜单">
          <UserOutlined />
          <span className="min-w-0 flex-1 truncate text-left">{user.displayName || user.username}</span>
          <Tag color={user.role === "ADMIN" ? "purple" : "blue"} className="m-0!">{user.role === "ADMIN" ? "管理员" : "用户"}</Tag>
          <DownOutlined className="text-xs" />
        </Button>
      </Dropdown>
      {passwordOpen && <PasswordDialog onClose={() => setPasswordOpen(false)} />}
    </div>
  );
}
