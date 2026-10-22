package com.mall.agent.trace;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TracerTest {

    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RequestTracer tracer = new RequestTracer(registry);

    @Test
    void recordsStepsInOrderWithTraceId() {
        tracer.step("route", "intent=ORDER");
        tracer.llm("router", "qwen-turbo", 120, new Usage(100, 20, false));
        tracer.tool("getOrder", "{\"orderNo\":\"x\"}", 35, true);

        Trace trace = tracer.finish();

        assertThat(trace.traceId()).isNotBlank().isEqualTo(tracer.traceId());
        assertThat(trace.steps()).extracting(TraceStep::type).containsExactly("step", "llm", "tool");
        assertThat(trace.steps()).extracting(TraceStep::name).containsExactly("route", "router", "getOrder");
        assertThat(trace.totalMillis()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void toolArgsAreMasked() {
        tracer.tool("getOrder", "{\"orderNo\":\"1234567890123456\",\"phone\":\"13800138000\"}", 10, true);
        String detail = tracer.finish().steps().get(0).detail();
        assertThat(detail).contains("************3456").contains("138****8000")
                .doesNotContain("1234567890123456").doesNotContain("13800138000");
    }

    @Test
    void stepDetailsAreMaskedToo() {
        tracer.step("rag.queries", "订单 2026100100000001 能退吗");
        assertThat(tracer.finish().steps().get(0).detail()).doesNotContain("2026100100000001").contains("************0001");
    }

    @Test
    void tokenTotalSumsLlmSteps() {
        tracer.llm("router", "m", 1, new Usage(100, 20, false));
        tracer.llm("expert", "m", 1, new Usage(500, 80, false));
        assertThat(tracer.finish().totalTokens()).isEqualTo(700);
        assertThat(registry.get("agent.tokens").tag("type", "prompt").counter().count()).isEqualTo(600);
        assertThat(registry.get("agent.tokens").tag("type", "completion").counter().count()).isEqualTo(100);
    }

    @Test
    void estimatedUsageIsFlagged() {
        tracer.llm("expert", "m", 1, new Usage(10, 5, true));
        assertThat(tracer.finish().steps().get(0).detail()).contains("estimated");
    }

    @Test
    void metricsIncrementOnToolFailure() {
        tracer.tool("getLogistics", "{}", 1500, false);
        tracer.tool("getLogistics", "{}", 20, true);
        assertThat(registry.get("agent.tool.calls").tag("tool", "getLogistics").tag("ok", "false").counter().count()).isEqualTo(1);
        assertThat(registry.get("agent.tool.calls").tag("tool", "getLogistics").tag("ok", "true").counter().count()).isEqualTo(1);
    }

    @Test
    void stageTimerAndRefusalCounter() {
        tracer.llm("rewrite", "m", 250, new Usage(1, 1, false));
        tracer.step("rag.refused", "below threshold");
        assertThat(registry.get("agent.stage.duration").tag("stage", "rewrite").timer().count()).isEqualTo(1);
        assertThat(registry.get("agent.rag.refusals").counter().count()).isEqualTo(1);
    }

    @Test
    void finishIsSafeToCallTwice() {
        tracer.step("a", "b");
        assertThat(tracer.finish().steps()).hasSize(1);
        assertThat(tracer.finish().steps()).hasSize(1);
    }
}
