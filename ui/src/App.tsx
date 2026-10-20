import { lazy, Suspense } from "react";
import { BrowserRouter } from "react-router-dom";
import JChatMindLayout from "./components/JChatMindLayout.tsx";
import { ChatSessionsProvider } from "./contexts/ChatSessionsContext.tsx";
import { IS_PUBLIC_DEMO } from "./config.ts";
import PublicDemoBootstrap from "./components/PublicDemoBootstrap.tsx";
import AccountProvider from "./contexts/AccountProvider.tsx";
import RouteFallback from "./components/RouteFallback.tsx";
import LazyBoundary from "./components/LazyBoundary.tsx";

// 账号工作区只在账号构建里出现：懒加载让游客首屏不必等管理端代码。
const AccountWorkspace = lazy(() => import("./components/AccountWorkspace.tsx"));

function App() {
  const workspace = (
    <ChatSessionsProvider>
      <JChatMindLayout />
    </ChatSessionsProvider>
  );

  return (
    <BrowserRouter basename={import.meta.env.BASE_URL}>
      {IS_PUBLIC_DEMO ? (
        <PublicDemoBootstrap>{workspace}</PublicDemoBootstrap>
      ) : (
        <AccountProvider>
          <LazyBoundary>
            <Suspense fallback={<RouteFallback label="正在准备账号工作区…" />}>
              <AccountWorkspace />
            </Suspense>
          </LazyBoundary>
        </AccountProvider>
      )}
    </BrowserRouter>
  );
}

export default App;
