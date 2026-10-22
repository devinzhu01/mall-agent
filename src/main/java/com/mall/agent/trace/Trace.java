package com.mall.agent.trace;

import java.util.List;

/** 一次请求的完整链路记录。 */
public record Trace(String traceId, List<TraceStep> steps, long totalMillis, int totalTokens) {
}
