import type { MessageInstance } from "antd/es/message/interface";

let feedback: MessageInstance | undefined;
const pending: { type: "error" | "success"; content: string }[] = [];

export function connectFeedback(instance: MessageInstance) {
  feedback = instance;
  pending.splice(0).forEach(({ type, content }) => { void instance[type](content); });
  return () => { if (feedback === instance) feedback = undefined; };
}

function publish(type: "error" | "success", content: string) {
  if (feedback) void feedback[type](content);
  else pending.push({ type, content });
}

export const notify = {
  error: (content: string) => publish("error", content),
  success: (content: string) => publish("success", content),
};
