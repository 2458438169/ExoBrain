import { BrowserRouter } from "react-router-dom";
import JChatMindLayout from "./components/JChatMindLayout.tsx";
import { ChatSessionsProvider } from "./contexts/ChatSessionsContext.tsx";
import { IS_PUBLIC_DEMO } from "./config.ts";
import PublicDemoBootstrap from "./components/PublicDemoBootstrap.tsx";

function App() {
  const workspace = (
    <ChatSessionsProvider>
      <JChatMindLayout />
    </ChatSessionsProvider>
  );

  return (
    <BrowserRouter basename={import.meta.env.BASE_URL}>
      {IS_PUBLIC_DEMO ? <PublicDemoBootstrap>{workspace}</PublicDemoBootstrap> : workspace}
    </BrowserRouter>
  );
}

export default App;
