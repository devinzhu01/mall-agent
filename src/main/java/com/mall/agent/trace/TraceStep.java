package com.mall.agent.trace;

/** type: step(普通步骤)/ llm(模型调用)/ tool(工具调用)。 */
public record TraceStep(String type, String name, String detail, long millis, boolean ok) {
}
