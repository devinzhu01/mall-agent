package com.mall.agent.tool;

/** 工具内部抛出的业务异常,由 GuardedToolCallback 统一转换成 ToolResult。 */
public class ToolException extends RuntimeException {

    private final String code;
    private final boolean retryable;
    private final String hint;

    public ToolException(String code, String message, boolean retryable, String hint) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.hint = hint;
    }

    public ToolResult toResult() {
        return ToolResult.fail(code, getMessage(), retryable, hint);
    }
}
