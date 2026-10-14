import { useLayoutEffect, type ReactNode } from "react";
import { message } from "antd";
import { connectFeedback } from "../api/feedback.ts";

export default function FeedbackProvider({ children }: { children: ReactNode }) {
  const [api, holder] = message.useMessage();
  useLayoutEffect(() => connectFeedback(api), [api]);
  return <>{holder}{children}</>;
}
