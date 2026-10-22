package com.mall.agent.agent;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.memory.MemoryContext;
import com.mall.agent.memory.MemoryService;
import com.mall.agent.tool.ToolBox;
import com.mall.agent.trace.RequestTracer;
import com.mall.agent.trace.Trace;
import com.mall.agent.trace.TraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次请求的完整链路(README 里的"链路走读"就按这个类讲):
 *   加载记忆 → 路由(single 模式跳过)→ 装配专家与工具 → 专家流式回答(内含工具调用循环)
 *   → 回答结束后:写记忆(异步)、结束 Trace。
 * open() 同步完成路由和装配,返回一个流;流结束时才落记忆,所以失败的回答不会污染历史。
 */
@Service
public class AgentOrchestrator {

    /** chunks 是回答的文本片段流;tracer 用于流结束后取完整 Trace。 */
    public record Run(String traceId, Flux<String> chunks, RequestTracer tracer, RouteDecision route) {
    }

    public record AgentReply(String text, Trace trace, RouteDecision route) {
    }

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final String APOLOGY = "抱歉,服务暂时不可用,请稍后再试。";
    private static final String EMPTY = "抱歉,我暂时没能给出回答,请换个说法再试一次。";

    private final MemoryService memory;
    private final RouterAgent router;
    private final ExpertFactory experts;
    private final ToolBox toolBox;
    private final ExpertChat expertChat;
    private final TraceService traces;
    private final AgentProperties props;

    public AgentOrchestrator(MemoryService memory, RouterAgent router, ExpertFactory experts, ToolBox toolBox,
                             ExpertChat expertChat, TraceService traces, AgentProperties props) {
        this.memory = memory;
        this.router = router;
        this.experts = experts;
        this.toolBox = toolBox;
        this.expertChat = expertChat;
        this.traces = traces;
        this.props = props;
    }

    public Run open(String userId, String sessionId, String message) {
        RequestTracer tracer = traces.start();
        try {
            MemoryContext ctx = memory.load(userId, sessionId);

            boolean single = "single".equalsIgnoreCase(props.getMode());
            RouteDecision route = single ? RouteDecision.allTools() : router.route(ctx, message, tracer);
            tracer.step("route", route.intent() == null ? (single ? "single-agent" : "undecided->all-tools") : "intent=" + route.intent() + " category=" + route.category());

            Flux<String> answer;
            if (route.intent() == Intent.CHITCHAT) {
                answer = Flux.just(route.reply());
            } else {
                Expert expert = experts.forIntent(route.intent(), ctx, route.category(), tracer);
                tracer.step("expert", expert.name());
                var tools = toolBox.build(expert.toolBeans(), tracer);
                tracer.step("tools.offered", java.util.Arrays.stream(tools).map(t -> t.getToolDefinition().name()).toList().toString());
                answer = expertChat.stream(expert.systemPrompt(), ctx.recent(), message, tools, userId, tracer);
            }
            return new Run(tracer.traceId(), finishWith(answer, userId, sessionId, message, tracer), tracer, route);
        } finally {
            traces.end();
        }
    }

    /** 阻塞版本:收集完整回答,给评测运行器用。 */
    public AgentReply ask(String userId, String sessionId, String message) {
        Run run = open(userId, sessionId, message);
        String text = String.join("", run.chunks().collectList().block());
        return new AgentReply(text, run.tracer().finish(), run.route());
    }

    private Flux<String> finishWith(Flux<String> answer, String userId, String sessionId, String message,
                                    RequestTracer tracer) {
        StringBuilder text = new StringBuilder();
        AtomicBoolean failed = new AtomicBoolean();
        return answer
                .doOnNext(text::append)
                .onErrorResume(e -> {
                    log.error("traceId={} expert failed", tracer.traceId(), e);
                    tracer.step("error", e.toString());
                    failed.set(true);
                    return Flux.just(APOLOGY);
                })
                .switchIfEmpty(Flux.just(EMPTY))
                .doOnComplete(() -> {
                    tracer.finish();
                    if (!failed.get() && text.length() > 0) {
                        // 落记忆涉及 Redis/MySQL/可能的摘要调用,放到弹性线程,不拖慢流的结束
                        Schedulers.boundedElastic().schedule(
                                () -> memory.save(userId, sessionId, message, text.toString(), tracer));
                    }
                });
    }
}
