import React from "react";

interface LazyBoundaryProps {
  children: React.ReactNode;
  /** 出错时的说明文案，默认覆盖"分包加载失败"。 */
  title?: string;
}

interface LazyBoundaryState {
  failed: boolean;
}

/**
 * 懒加载边界的错误态。
 *
 * <p>分包下载失败（刚发布新版本、网络中断）不应该白屏，也不该自动刷新把用户正在读的内容冲掉。
 * 这里显示可读说明和一个**用户主动**的重新加载按钮；草稿与幂等意图由调用方保存在
 * {@code sessionStorage} 里，重新加载后恢复，已受理的运行从服务端重新读取，不会再次提交。</p>
 */
class LazyBoundary extends React.Component<LazyBoundaryProps, LazyBoundaryState> {
  state: LazyBoundaryState = { failed: false };

  static getDerivedStateFromError(): LazyBoundaryState {
    return { failed: true };
  }

  componentDidCatch(error: unknown) {
    // 只记录类型与消息，不打整段堆栈到用户界面。
    console.error("lazy chunk failed", error instanceof Error ? error.message : error);
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <div className="mx-auto w-full max-w-xl px-4 py-10 sm:px-8" role="alert">
        <p className="text-base font-bold text-ink">{this.props.title ?? "页面资源没有加载成功"}</p>
        <p className="mt-2 text-sm leading-6 text-slate-600">
          可能是网络中断，或者站点刚发布了新版本。你已经输入的问题会保留，重新加载后继续；已提交的问题不会重复发送。
        </p>
        <div className="mt-4 flex flex-wrap gap-2">
          <button
            type="button"
            className="rounded-full bg-ink px-4 py-2 text-sm font-semibold text-white"
            onClick={() => window.location.reload()}
          >
            重新加载页面
          </button>
          <a className="rounded-full border border-sketch px-4 py-2 text-sm font-semibold text-ink" href="/chat">
            返回对话
          </a>
        </div>
      </div>
    );
  }
}

export default LazyBoundary;
