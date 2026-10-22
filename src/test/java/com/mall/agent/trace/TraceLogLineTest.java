package com.mall.agent.trace;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceLogLineTest {

    @Test
    void routeFieldComesFromTheRouteStepNotFromTheRouterLlmCall() {
        RequestTracer tracer = new RequestTracer(new SimpleMeterRegistry());
        tracer.llm("route", "qwen-plus", 300, new Usage(900, 94, false));   // 路由的模型调用,阶段名也叫 route
        tracer.step("route", "intent=ORDER category=null");
        tracer.step("tools.offered", "[getOrder, listMyOrders]");
        tracer.tool("listMyOrders", "{}", 20, true);

        String line = tracer.logLine(123);

        assertThat(line).contains("route=intent=ORDER category=null")
                .contains("offered=[getOrder, listMyOrders]")
                .contains("tools=[listMyOrders]")
                .contains("tokens=994")
                .contains("ms=123")
                .doesNotContain("route=model=");
    }

    @Test
    void emptyToolListIsExplicit() {
        RequestTracer tracer = new RequestTracer(new SimpleMeterRegistry());
        tracer.step("route", "single-agent");
        assertThat(tracer.logLine(5)).contains("tools=[]");
    }
}
