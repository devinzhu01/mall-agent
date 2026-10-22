package com.mall.agent.agent;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.memory.MemoryContext;
import com.mall.agent.memory.MemoryService;
import com.mall.agent.tool.ToolBox;
import com.mall.agent.trace.TraceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 排查"模型没调用工具"时最需要的一条线索:这次到底把哪些工具给了模型。 */
class OrchestratorToolsOfferedTest {

    private static ToolCallback named(String name) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("d").inputSchema("{}").build();
            }
            @Override public String call(String in) { return "{}"; }
        };
    }

    @Test
    void traceRecordsTheToolsOfferedToTheModel() {
        MemoryService memory = mock(MemoryService.class);
        RouterAgent router = mock(RouterAgent.class);
        ExpertFactory factory = mock(ExpertFactory.class);
        ToolBox toolBox = mock(ToolBox.class);
        ExpertChat chat = mock(ExpertChat.class);
        when(memory.load("u1", "s1")).thenReturn(new MemoryContext("", List.of(), List.of()));
        when(router.route(any(), anyString(), any())).thenReturn(RouteDecision.allTools());
        when(factory.forIntent(any(), any(), any(), any())).thenReturn(new Expert("all", "SYS", List.of(), Set.of()));
        when(toolBox.build(anyList(), any())).thenReturn(new ToolCallback[]{named("searchProducts"), named("getOrder")});
        when(chat.stream(anyString(), anyList(), anyString(), any(), anyString(), any())).thenReturn(Flux.just("ok"));
        var orchestrator = new AgentOrchestrator(memory, router, factory, toolBox, chat,
                new TraceService(new SimpleMeterRegistry()), new AgentProperties());

        var reply = orchestrator.ask("u1", "s1", "推荐一部手机");

        assertThat(reply.trace().steps()).anyMatch(s -> s.name().equals("tools.offered")
                && s.detail().contains("searchProducts") && s.detail().contains("getOrder"));
    }
}
