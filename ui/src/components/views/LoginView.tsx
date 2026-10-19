import { useState } from "react";
import { Alert, Button, Form, Input, Typography } from "antd";
import { ArrowLeftOutlined, LockOutlined, UserOutlined } from "@ant-design/icons";
import { useAccount } from "../../contexts/accountState.ts";
import ExoBrainLogo from "../ExoBrainLogo.tsx";

export default function LoginView() {
  const account = useAccount();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const submit = async ({ username, password }: { username: string; password: string }) => {
    if (!account || busy) return;
    setBusy(true);
    setError("");
    try {
      await account.login(username.trim(), password);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "登录失败，请重试");
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="flex min-h-dvh items-center justify-center px-5 py-10">
      <div className="w-full max-w-md">
        <div className="mb-7 flex items-center justify-center gap-3 animate-pop-in">
          <ExoBrainLogo className="h-11 w-11 rounded-2xl shadow-doodle" />
          <span className="text-2xl font-bold tracking-tight text-ink">ExoBrain</span>
        </div>
        <section className="doodle-card p-6 sm:p-8 animate-pop-in" style={{ animationDelay: "60ms" }} aria-labelledby="account-login-title">
          <Typography.Title level={2} id="account-login-title" className="mb-2! text-2xl!">
            账号登录 <span className="doodle-squiggle">✎</span>
          </Typography.Title>
          <p className="mb-7 text-sm leading-6 text-slate-500">登录后使用你的助手、知识库和聊天记录。</p>
          {error && <Alert type="error" showIcon title={error} className="mb-5!" />}
          <Form layout="vertical" onFinish={submit} requiredMark={false} autoComplete="on">
            <Form.Item name="username" label="账号" rules={[{ required: true, whitespace: true, message: "请输入账号" }]}>
              <Input prefix={<UserOutlined className="text-slate-400" />} autoComplete="username" placeholder="输入账号" maxLength={32} size="large" autoFocus />
            </Form.Item>
            <Form.Item name="password" label="密码" rules={[{ required: true, message: "请输入密码" }]}>
              <Input.Password prefix={<LockOutlined className="text-slate-400" />} autoComplete="current-password" placeholder="输入密码" maxLength={128} size="large" />
            </Form.Item>
            <Button type="primary" htmlType="submit" size="large" block loading={busy} className="mt-2!">登录</Button>
          </Form>
          <p className="mt-5 text-center text-xs leading-6 text-slate-400">账号由管理员创建。如需账号或重置密码，请联系管理员。</p>
        </section>
        <div className="mt-6 text-center">
          <Button type="link" href="/" icon={<ArrowLeftOutlined />}>游客免登录体验</Button>
        </div>
      </div>
    </main>
  );
}
