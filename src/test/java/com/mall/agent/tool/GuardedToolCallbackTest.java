package com.mall.agent.tool;

import com.mall.agent.trace.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class GuardedToolCallbackTest {

    private static final ToolDefinition DEF =
            ToolDefinition.builder().name("t").description("d").inputSchema("{}").build();

    private static ToolCallback stub(Function<String, String> body, AtomicInteger calls) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return DEF; }
            @Override public String call(String in) { calls.incrementAndGet(); return body.apply(in); }
        };
    }

    private static GuardedToolCallback guard(ToolCallback d, AtomicInteger counter, int max) {
        return new GuardedToolCallback(d, counter, max, Tracer.NOOP);
    }

    @Test
    void toolExceptionBecomesStructuredError() {
        var cb = guard(stub(in -> { throw new ToolException("INVALID_ARG", "订单号格式不对", true, "订单号为 16 位数字"); },
                new AtomicInteger()), new AtomicInteger(), 3);
        String json = cb.call("{}");
        assertThat(json).contains("\"ok\":false").contains("INVALID_ARG").contains("\"hint\":\"订单号为 16 位数字\"")
                .contains("\"retryable\":true");
    }

    @Test
    void unwrapsToolExecutionExceptionFromSpringAi() {
        var cb = guard(stub(in -> { throw new ToolExecutionException(DEF,
                new ToolException("NOT_FOUND", "未找到该订单", false, null)); }, new AtomicInteger()), new AtomicInteger(), 3);
        assertThat(cb.call("{}")).contains("NOT_FOUND").doesNotContain("INTERNAL");
    }

    @Test
    void unexpectedExceptionBecomesInternalError() {
        var cb = guard(stub(in -> { throw new IllegalStateException("boom"); }, new AtomicInteger()), new AtomicInteger(), 3);
        String json = cb.call("{}");
        assertThat(json).contains("INTERNAL").contains("\"retryable\":false").doesNotContain("boom");
    }

    @Test
    void stopsExecutingAfterMaxFailures() {
        var delegateCalls = new AtomicInteger();
        var cb = guard(stub(in -> { throw new ToolException("X", "fail", true, null); }, delegateCalls), new AtomicInteger(), 2);
        cb.call("{}");
        cb.call("{}");
        String third = cb.call("{}");
        assertThat(delegateCalls.get()).isEqualTo(2);
        assertThat(third).contains("TOO_MANY_FAILURES").contains("\"retryable\":false").contains("澄清");
    }

    @Test
    void successDoesNotCountAsFailure() {
        var counter = new AtomicInteger();
        var cb = guard(stub(in -> "{\"ok\":true}", new AtomicInteger()), counter, 1);
        cb.call("{}");
        cb.call("{}");
        assertThat(counter.get()).isZero();
        assertThat(cb.call("{}")).isEqualTo("{\"ok\":true}");
    }

    static class Tools1 {
        @Tool(description = "a") String a() { throw new ToolException("X", "fail", true, null); }
    }

    static class Tools2 {
        @Tool(description = "b") String b() { throw new ToolException("X", "fail", true, null); }
    }

    @Test
    void budgetSharedAcrossToolsInOneBuild() {
        var box = new ToolBox(new com.mall.agent.config.AgentProperties() {{ getTool().setMaxFailures(2); }});
        ToolCallback[] cbs = box.build(List.of(new Tools1(), new Tools2()), Tracer.NOOP);
        var ctx = new ToolContext(Map.of());
        cbs[0].call("{}", ctx);
        cbs[1].call("{}", ctx);
        assertThat(cbs[0].call("{}", ctx)).contains("TOO_MANY_FAILURES");
        // 另一次 build 拥有新的预算
        ToolCallback[] fresh = box.build(List.of(new Tools1()), Tracer.NOOP);
        assertThat(fresh[0].call("{}", ctx)).doesNotContain("TOO_MANY_FAILURES");
    }
}
