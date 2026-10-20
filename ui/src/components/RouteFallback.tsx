import React from "react";

/**
 * 首屏骨架：只用原生元素，不引入 antd，保证"页面开始画"不被懒加载 chunk 拖住。
 *
 * <p>它是占位而不是可用状态：推荐问题按钮出现并且可点击之后才算首屏可用（UX-07 的测量口径）。</p>
 */
const RouteFallback: React.FC<{ label?: string }> = ({ label = "正在准备页面…" }) => (
  <div className="mx-auto w-full max-w-2xl animate-pulse px-4 py-8 sm:px-8" aria-busy="true" aria-live="polite">
    <span className="sr-only">{label}</span>
    <div className="mb-3 h-8 w-40 rounded-lg bg-sketch/60" />
    <div className="mb-6 h-4 w-3/4 rounded bg-sketch/40" />
    <div className="mb-3 h-14 w-full rounded-2xl bg-sketch/50" />
    <div className="mb-3 h-14 w-full rounded-2xl bg-sketch/40" />
    <div className="h-14 w-full rounded-2xl bg-sketch/30" />
  </div>
);

export default RouteFallback;
