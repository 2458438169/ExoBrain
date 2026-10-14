import { Button, Result, Spin } from "antd";
import { useAccount } from "../contexts/accountState.ts";
import { ChatSessionsProvider } from "../contexts/ChatSessionsContext.tsx";
import JChatMindLayout from "./JChatMindLayout.tsx";
import LoginView from "./views/LoginView.tsx";

export default function AccountWorkspace() {
  const account = useAccount();
  if (!account || account.status === "loading") {
    return <div className="flex h-dvh flex-col items-center justify-center gap-3"><Spin /><span className="text-sm text-slate-500">正在连接账号工作区…</span></div>;
  }
  if (account.status === "error") {
    return <div className="flex h-dvh items-center justify-center p-5"><Result status="warning" title="暂时无法连接" subTitle={account.error} extra={<Button type="primary" onClick={() => void account.refresh()}>重试</Button>} /></div>;
  }
  if (account.status !== "signedIn" || !account.user) return <LoginView />;
  return (
    <ChatSessionsProvider key={`${account.user.id}:${account.sessionVersion}`}>
      <JChatMindLayout />
    </ChatSessionsProvider>
  );
}
