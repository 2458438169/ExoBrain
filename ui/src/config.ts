export const IS_PUBLIC_DEMO = import.meta.env.VITE_PUBLIC_DEMO === "true";

export const DEMO_PROMPTS = [
  {
    title: "了解项目架构",
    description: "从公开资料了解技术栈与 Agent 流程",
    prompt: "请检索公开知识库，介绍 ExoBrain 的技术栈和 Agent 的运行流程，并注明文档来源。",
  },
  {
    title: "体验知识检索",
    description: "追踪 Markdown 文档如何变成可检索资料",
    prompt: "请检索公开知识库，ExoBrain 如何把 Markdown 文档变成可检索的资料？请注明文档来源。",
  },
  {
    title: "验证工具调用",
    description: "调用日期工具，查看实际返回的结果",
    prompt: "请调用日期工具告诉我今天的日期。",
  },
] as const;
