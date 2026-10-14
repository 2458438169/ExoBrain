import { BrowserRouter } from "react-router-dom";
import JChatMindLayout from "./components/JChatMindLayout.tsx";
import { ChatSessionsProvider } from "./contexts/ChatSessionsContext.tsx";
import { IS_PUBLIC_DEMO } from "./config.ts";
import PublicDemoBootstrap from "./components/PublicDemoBootstrap.tsx";
import AccountProvider from "./contexts/AccountProvider.tsx";
import AccountWorkspace from "./components/AccountWorkspace.tsx";

function App() {
  const workspace = (
    <ChatSessionsProvider>
      <JChatMindLayout />
    </ChatSessionsProvider>
  );

  return (
    <BrowserRouter basename={import.meta.env.BASE_URL}>
      {IS_PUBLIC_DEMO ? <PublicDemoBootstrap>{workspace}</PublicDemoBootstrap> : <AccountProvider><AccountWorkspace /></AccountProvider>}
    </BrowserRouter>
  );
}

export default App;
