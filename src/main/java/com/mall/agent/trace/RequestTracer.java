package com.mall.agent.trace;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 一个请求一个实例,显式往下传(不放 ThreadLocal:流式回答和工具调用会跨线程)。
 * 同时做两件事:把每一步存下来(返回给前端/评测使用),并上报 Micrometer 指标。
 * 所有文本入库前先脱敏。
 */
public class RequestTracer implements Tracer {

    private static final Logger log = LoggerFactory.getLogger("agent.trace");

    private final MeterRegistry meters;
    private final String traceId = UUID.randomUUID().toString().substring(0, 8);
    private final long startNanos = System.nanoTime();
    private final List<TraceStep> steps = new CopyOnWriteArrayList<>();
    private final AtomicInteger totalTokens = new AtomicInteger();

    public RequestTracer(MeterRegistry meters) {
        this.meters = meters;
    }

    public String traceId() {
        return traceId;
    }

    @Override
    public void step(String name, String detail) {
        steps.add(new TraceStep("step", name, Masker.mask(detail), 0, true));
        if (name.equals("rag.refused")) {
            meters.counter("agent.rag.refusals").increment();
        }
    }

    @Override
    public void llm(String stage, String model, long millis, Usage usage) {
        String detail = "model=" + model + " tokens=" + usage.total() + (usage.estimated() ? " (estimated)" : "");
        steps.add(new TraceStep("llm", stage, detail, millis, true));
        totalTokens.addAndGet(usage.total());
        meters.timer("agent.stage.duration", "stage", stage).record(Duration.ofMillis(millis));
        meters.counter("agent.tokens", "type", "prompt").increment(usage.promptTokens());
        meters.counter("agent.tokens", "type", "completion").increment(usage.completionTokens());
    }

    @Override
    public void tool(String name, String argsJson, long millis, boolean ok) {
        steps.add(new TraceStep("tool", name, Masker.mask(argsJson), millis, ok));
        meters.counter("agent.tool.calls", "tool", name, "ok", String.valueOf(ok)).increment();
        meters.timer("agent.stage.duration", "stage", "tool").record(Duration.ofMillis(millis));
    }

    /** 汇总并输出一行结构化日志;可重复调用。 */
    public Trace finish() {
        long totalMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        Trace trace = new Trace(traceId, List.copyOf(steps), totalMillis, totalTokens.get());
        meters.timer("agent.stage.duration", "stage", "total").record(Duration.ofMillis(totalMillis));
        String route = steps.stream().filter(s -> s.name().equals("route")).map(TraceStep::detail)
                .findFirst().orElse("-");
        String tools = steps.stream().filter(s -> s.type().equals("tool"))
                .map(s -> s.name() + (s.ok() ? "" : "(failed)")).collect(Collectors.joining(","));
        log.info("traceId={} route={} tools=[{}] tokens={} ms={}", traceId, route, tools, trace.totalTokens(), totalMillis);
        return trace;
    }
}
