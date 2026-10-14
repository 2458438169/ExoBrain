package com.kama.jchatmind.exception;

/** Preview failures use fixed messages so filesystem details never reach the client. */
public final class DocumentContentException extends RuntimeException {
    private final int status;

    private DocumentContentException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() { return status; }

    public static DocumentContentException notFound() {
        return new DocumentContentException(404, "文档不存在");
    }

    public static DocumentContentException unavailable() {
        return new DocumentContentException(404, "文档原文不可用");
    }

    public static DocumentContentException tooLarge() {
        return new DocumentContentException(413, "文档超过 2 MiB，无法预览");
    }

    public static DocumentContentException unsupportedType() {
        return new DocumentContentException(415, "仅支持 Markdown 文档预览");
    }

    public static DocumentContentException invalidEncoding() {
        return new DocumentContentException(422, "文档不是有效的 UTF-8 文本");
    }

    public static DocumentContentException readFailed() {
        return new DocumentContentException(500, "读取文档原文失败");
    }
}
