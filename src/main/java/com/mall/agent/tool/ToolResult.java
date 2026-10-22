package com.mall.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工具返回给模型的统一结构。失败也是"结果"而不是异常:
 * 模型看到 errorCode/hint 后才能自我纠正(改参数、换工具、或向用户澄清)。
 */
public record ToolResult(boolean ok, Object data, String errorCode, String message, boolean retryable, String hint) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ToolResult ok(Object data) {
        return new ToolResult(true, data, null, null, false, null);
    }

    public static ToolResult fail(String code, String message, boolean retryable, String hint) {
        return new ToolResult(false, null, code, message, retryable, hint);
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            return "{\"ok\":false,\"errorCode\":\"INTERNAL\",\"retryable\":false}";
        }
    }
}
