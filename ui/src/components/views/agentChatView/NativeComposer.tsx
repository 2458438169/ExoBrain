import React, { useEffect, useRef } from "react";

interface NativeComposerProps {
  value: string;
  onChange: (value: string) => void;
  /** 返回 true 表示服务端已确认受理。 */
  onSubmit: (value: string) => void | Promise<void>;
  /** 本轮正在执行：发送按钮变成停止按钮，但输入框仍可编辑。 */
  loading?: boolean;
  onCancel?: () => void;
  canCancel?: boolean;
  cancelling?: boolean;
  disabled?: boolean;
  placeholder?: string;
  sendLabel?: string;
  loadingLabel?: string;
  autoFocus?: boolean;
}

/**
 * 原生输入区：textarea + 发送/停止按钮，不引入 `@ant-design/x` 的 Sender。
 *
 * <p>原因很直接：Sender 会把整份 antd-x 带进首屏，而首屏只需要一个能提交问题的输入框（UX-07 的
 * 首屏 JS 预算）。行为保持不变：Enter 发送、Shift+Enter 换行、中文输入法组合期间不提交、
 * 等待期间仍可写下一句、停止按钮走真实取消。</p>
 */
const NativeComposer: React.FC<NativeComposerProps> = ({
  value,
  onChange,
  onSubmit,
  loading = false,
  onCancel,
  canCancel = false,
  cancelling = false,
  disabled = false,
  placeholder,
  sendLabel = "发送问题",
  loadingLabel = "正在提交",
  autoFocus = false,
}) => {
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const composingRef = useRef(false);

  useEffect(() => {
    const node = textareaRef.current;
    if (!node) return;
    node.style.height = "auto";
    node.style.height = `${Math.min(node.scrollHeight, 132)}px`;
  }, [value]);

  const canSend = !!value.trim() && !loading && !disabled;

  const submit = () => {
    if (!canSend) return;
    void onSubmit(value.trim());
  };

  return (
    <div className="flex min-w-0 items-end gap-2 rounded-2xl border-2 border-sketch bg-white px-3 py-2 shadow-doodle focus-within:border-ink/60">
      <textarea
        ref={textareaRef}
        value={value}
        rows={1}
        autoFocus={autoFocus}
        disabled={disabled}
        placeholder={placeholder}
        aria-label="问题输入框"
        className="min-h-9 max-h-[132px] w-full flex-1 resize-none bg-transparent py-1 text-sm leading-6 text-ink outline-none placeholder:text-slate-400 disabled:cursor-not-allowed"
        onChange={(event) => onChange(event.target.value)}
        onCompositionStart={() => { composingRef.current = true; }}
        onCompositionEnd={() => { composingRef.current = false; }}
        onKeyDown={(event) => {
          if (event.key !== "Enter" || event.shiftKey) return;
          // 中文输入法按 Enter 选词时不要提交。
          if (composingRef.current || event.nativeEvent.isComposing) return;
          event.preventDefault();
          submit();
        }}
      />
      {loading ? (
        <button
          type="button"
          aria-label={cancelling ? "正在取消回答" : "停止本次回答"}
          disabled={!canCancel || cancelling}
          onClick={onCancel}
          className="mb-0.5 inline-flex h-9 shrink-0 items-center gap-1 rounded-full bg-ink px-3 text-xs font-semibold text-white disabled:cursor-not-allowed disabled:opacity-50"
        >
          <span aria-hidden="true" className="block h-2.5 w-2.5 rounded-[2px] bg-white" />
          {cancelling ? "正在取消" : "停止"}
        </button>
      ) : (
        <button
          type="button"
          aria-label={sendLabel}
          disabled={!canSend}
          onClick={submit}
          className="mb-0.5 inline-flex h-9 shrink-0 items-center gap-1 rounded-full bg-ink px-3 text-xs font-semibold text-white disabled:cursor-not-allowed disabled:opacity-40"
        >
          {loadingLabel === "正在提交" ? "发送" : loadingLabel}
        </button>
      )}
    </div>
  );
};

export default NativeComposer;
