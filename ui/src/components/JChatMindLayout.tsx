import { Routes, Route } from "react-router-dom";
import { useState } from "react";
import { Button, Drawer } from "antd";
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

export default function JChatMindLayout() {
  const [menuOpen, setMenuOpen] = useState(false);
  return (
    <Layout>
      <div className="flex h-14 shrink-0 items-center gap-3 border-b border-slate-200 px-4 md:hidden">
        <Button type="text" icon={<MenuOutlined />} aria-label="打开导航菜单" onClick={() => setMenuOpen(true)} />
        <ExoBrainLogo className="h-7 w-7" />
        <span className="text-lg font-semibold text-slate-900">ExoBrain</span>
        {IS_PUBLIC_DEMO && <span className="ml-auto text-xs text-indigo-600">公开演示</span>}
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
          <Route path="*" element={<AgentChatView />} />
        </Routes>
      </Content>
    </Layout>
  );
}
