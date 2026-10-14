import { Routes, Route, Link } from "react-router-dom";
import { useState, type ReactNode } from "react";
import { Button, Drawer, Result } from "antd";
import { MenuOutlined } from "@ant-design/icons";
import Layout from "../layout/Layout.tsx";
import Sidebar from "../layout/Sidebar.tsx";
import SideMenu from "./SideMenu.tsx";
import Content from "../layout/Content.tsx";
import AgentChatView from "./views/AgentChatView.tsx";
import KnowledgeBaseView from "./views/KnowledgeBaseView.tsx";
import PublicShowcaseView from "./views/PublicShowcaseView.tsx";
import { IS_PUBLIC_DEMO } from "../config.ts";
import ExoBrainLogo from "./ExoBrainLogo.tsx";
import { useAccount } from "../contexts/accountState.ts";
import UsersManagementView from "./views/UsersManagementView.tsx";
import ModelsManagementView from "./views/ModelsManagementView.tsx";

function AdminOnly({ children }: { children: ReactNode }) {
  const account = useAccount();
  return account?.user?.role === "ADMIN" ? children : <Result status="403" title="需要管理员权限" subTitle="你的账号可使用助手、知识库与聊天记录。" extra={<Link to="/chat"><Button type="primary">返回工作区</Button></Link>} />;
}

export default function JChatMindLayout() {
  const [menuOpen, setMenuOpen] = useState(false);
  const account = useAccount();
  return (
    <Layout>
      <div className="flex h-14 shrink-0 items-center gap-3 border-b border-slate-200 px-4 md:hidden">
        <Button type="text" icon={<MenuOutlined />} aria-label="打开导航菜单" onClick={() => setMenuOpen(true)} />
        <ExoBrainLogo className="h-7 w-7" />
        <span className="min-w-0 flex-1 text-lg font-semibold text-slate-900">ExoBrain</span>
        {IS_PUBLIC_DEMO ? <Button type="link" href="/admin/" size="small">账号登录</Button> : <span className="text-xs text-indigo-600">{account?.user?.role === "ADMIN" ? "管理员" : "账号工作区"}</span>}
      </div>
      <Drawer open={menuOpen} onClose={() => setMenuOpen(false)} placement="left" title="导航" size={320} styles={{ body: { padding: 0 } }} destroyOnHidden>
        <SideMenu onNavigate={() => setMenuOpen(false)} />
      </Drawer>
      <Sidebar>
        <SideMenu />
      </Sidebar>
      <Content>
        <Routes>
          <Route path="/" element={<AgentChatView />} />
          <Route path="/agent" element={<AgentChatView />} />
          <Route path="/chat" element={<AgentChatView />} />
          <Route path="/chat/:chatSessionId" element={<AgentChatView />} />
          {IS_PUBLIC_DEMO && <Route path="/showcase" element={<PublicShowcaseView />} />}
          {IS_PUBLIC_DEMO && <Route path="/showcase/:showcaseId" element={<PublicShowcaseView />} />}
          <Route path="/knowledge-base" element={<KnowledgeBaseView />} />
          <Route
            path="/knowledge-base/:knowledgeBaseId"
            element={<KnowledgeBaseView />}
          />
          {!IS_PUBLIC_DEMO && <Route path="/users" element={<AdminOnly><UsersManagementView /></AdminOnly>} />}
          {!IS_PUBLIC_DEMO && <Route path="/models" element={<AdminOnly><ModelsManagementView /></AdminOnly>} />}
          <Route path="*" element={<AgentChatView />} />
        </Routes>
      </Content>
    </Layout>
  );
}
