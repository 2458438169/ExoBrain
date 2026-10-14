import { useEffect, useState, type ReactNode } from "react";
import { Button, Spin } from "antd";
import { ReloadOutlined } from "@ant-design/icons";
import { initializeDemoVisitor } from "../api/demo.ts";
import ExoBrainLogo from "./ExoBrainLogo.tsx";

export default function PublicDemoBootstrap({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let active = true;
    void initializeDemoVisitor().then(() => {
      if (active) setStatus("ready");
    }).catch(() => {
      if (active) setStatus("error");
    });
    return () => { active = false; };
  }, [attempt]);

  if (status === "ready") return children;

  return (
    <div className="flex h-dvh items-center justify-center bg-white p-6">
      <div className="max-w-sm text-center">
        <ExoBrainLogo className="mx-auto mb-5 h-14 w-14" />
        <h1 className="mb-3 text-2xl font-semibold text-slate-900">ExoBrain</h1>
        {status === "loading" ? (
          <div role="status" className="space-y-4 text-sm text-slate-500">
            <p>正在准备公开体验…</p>
            <Spin />
          </div>
        ) : (
          <div role="alert">
            <p className="mb-5 text-sm leading-6 text-slate-500">公开体验暂时无法连接，请检查网络后重试。</p>
            <Button type="primary" icon={<ReloadOutlined />} onClick={() => {
              setStatus("loading");
              setAttempt((value) => value + 1);
            }}>重新连接</Button>
          </div>
        )}
      </div>
    </div>
  );
}
