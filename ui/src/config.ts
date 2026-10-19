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
      "请用一句话和最多三个要点说明 ExoBrain 是做什么的、访客可以亲自体验哪些能力。只依据公开资料回答，并注明来源文档。",
  },
  {
    title: "看一条有原文出处的回答",
    description: "回答带可核对的原文位置",
    prompt:
      "请针对 ExoBrain 如何让我核对一个回答的依据给出回答，并注明引用的来源文档与段落，让我可以打开原文核对。",
  },
  {
    title: "作者新增了哪些能力",
    description: "区分上游已有与本仓库新增",
    prompt:
      "请区分上游项目已有的能力与作者在本仓库新增的能力，选出三个有公开资料证据的改进，并注明来源文档。",
  },
] as const;

/**
 * 回答之后的相关追问（UX-03 第 7 步：至多两条，选择后才发起新的运行）。
 * 只有点击才会发送，不会随着答案自动提交。
 */
export const FOLLOW_UP_PROMPTS: readonly string[] = [
  "这些结论的原文在哪个文档里？可以指出我可以核对的段落吗？",
  "作者新增的能力有公开证据吗？分别对应哪份资料？",
] as const;
