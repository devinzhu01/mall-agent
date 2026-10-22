package com.mall.agent.tool;

import com.mall.agent.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 链路位置:模型决定调用工具 → 这里 → 真正的 @Tool 方法。
 * 做三件事:1) 异常转结构化错误,让模型自我纠正;2) 同一请求内失败次数封顶,防止死循环重试;
 * 3) 记录每次调用的耗时与成败。失败计数器由 ToolBox 按请求创建并共享。
 */
public class GuardedToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(GuardedToolCallback.class);

    private final ToolCallback delegate;
    private final AtomicInteger failures;
    private final int maxFailures;
    private final Tracer tracer;

    public GuardedToolCallback(ToolCallback delegate, AtomicInteger failures, int maxFailures, Tracer tracer) {
        this.delegate = delegate;
        this.failures = failures;
        this.maxFailures = maxFailures;
        this.tracer = tracer;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = delegate.getToolDefinition().name();
        if (failures.get() >= maxFailures) {
            tracer.tool(name, toolInput, 0, false);
            return ToolResult.fail("TOO_MANY_FAILURES", "本次请求中工具已多次失败", false,
                    "请停止重试,向用户说明情况并请求澄清").toJson();
        }
        long start = System.currentTimeMillis();
        try {
            String result = delegate.call(toolInput, toolContext);
            tracer.tool(name, toolInput, System.currentTimeMillis() - start, true);
            return result;
        } catch (Throwable e) {
            failures.incrementAndGet();
            tracer.tool(name, toolInput, System.currentTimeMillis() - start, false);
            return toResult(name, e).toJson();
        }
    }

    /** Spring AI 会把工具方法里的异常包一层 ToolExecutionException,这里沿 cause 链找业务异常。 */
    private static ToolResult toResult(String name, Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ToolException te) {
                return te.toResult();
            }
        }
        log.warn("tool {} failed unexpectedly", name, e);
        return ToolResult.fail("INTERNAL", "工具内部错误", false, "请向用户说明暂时无法完成该操作");
    }
}
