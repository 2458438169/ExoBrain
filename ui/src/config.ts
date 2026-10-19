export const IS_PUBLIC_DEMO = import.meta.env.VITE_PUBLIC_DEMO === "true";

/**
 * 首页推荐问题（UX-03）。
 *
 * 文案与 UX-02 的资料内容一起校对：三个问题分别验证「30 秒读懂项目」「有原文出处的引用」
 * 「继承与新增的贡献边界」。提示词要求只依据公开资料回答并注明来源，避免把演示话术当成答案。
 * 点击后直接创建发送意图并发送，不需要先填输入框。
 */
export interface DemoPrompt {
  /** 展示文案，手机首屏用短句。 */
  title: string;
  /** 一行说明这个问题验证什么能力。 */
  description: string;
  /** 实际发送给 Agent 的问题原文。 */
  prompt: string;
}

export const DEMO_PROMPTS: readonly DemoPrompt[] = [
  {
    title: "30 秒了解这个项目",
    description: "一句话加三个要点，说明能做什么",
    prompt:
      "用一句话和三个要点说明 ExoBrain 能做什么，并指出我可以亲自体验的能力。",
  },
  {
    title: "看一条有原文出处的回答",
    description: "回答带可核对的原文位置",
    prompt:
      "这个项目如何让我核对答案的依据？请说明引用和原文的关系。",
  },
  {
    title: "作者新增了哪些能力",
    description: "区分上游已有与本仓库新增",
    prompt:
      "在上游 JChatMind 的基础上，这个仓库新增了哪些能力？请区分继承和新增。",
  },
] as const;

/**
 * 回答之后的相关追问（UX-03 第 7 步：至多两条，选择后才发起新的运行）。
 * 只有点击才会发送，不会随着答案自动提交。
 */
export const FOLLOW_UP_PROMPTS: readonly string[] = [
  "重复点击发送会不会跑两次？",
  "点了取消之后模型又返回了，会发生什么？",
] as const;
