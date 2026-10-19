import { createRoot } from "react-dom/client";
import { ConfigProvider } from "antd";
import zhCN from "antd/locale/zh_CN";
import App from "./App.tsx";
import FeedbackProvider from "./components/FeedbackProvider.tsx";
import "./index.css";

createRoot(document.getElementById("root")!).render(
  <ConfigProvider
    locale={zhCN}
    theme={{
      token: {
        colorPrimary: "#2b2724",
        colorInfo: "#3b6ea5",
        colorLink: "#3b6ea5",
        colorText: "#2b2724",
        colorTextSecondary: "#7a746d",
        colorBorder: "#e2ddd4",
        colorBgLayout: "#faf7f2",
        borderRadius: 14,
        fontWeightStrong: 700,
      },
      components: {
        Button: { borderRadius: 999, controlHeight: 36, fontWeight: 600 },
        Input: { borderRadius: 14 },
        Select: { borderRadius: 999 },
        Card: { borderRadius: 18 },
      },
    }}
  >
    <FeedbackProvider><App /></FeedbackProvider>
  </ConfigProvider>
);
