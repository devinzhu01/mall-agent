package com.mall.agent.agent;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.memory.MemoryContext;
import com.mall.agent.memory.MemoryService;
import com.mall.agent.tool.ToolBox;
import com.mall.agent.trace.TraceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouterFallbackOrchestrationTest {

    @Test
    void undecidedRouteBuildsTheAllToolsExpert() {
        MemoryService memory = mock(MemoryService.class);
        RouterAgent router = mock(RouterAgent.class);
        ExpertFactory factory = mock(ExpertFactory.class);
        ToolBox toolBox = mock(ToolBox.class);
        ExpertChat chat = mock(ExpertChat.class);
        when(memory.load("u1", "s1")).thenReturn(new MemoryContext("", List.of(), List.of()));
        when(router.route(any(), anyString(), any())).thenReturn(RouteDecision.allTools());
        when(factory.forIntent(any(), any(), any(), any()))
                .thenReturn(new Expert("all", "SYS", List.of(), Set.of()));
        when(toolBox.build(anyList(), any())).thenReturn(new ToolCallback[0]);
        when(chat.stream(anyString(), anyList(), anyString(), any(), anyString(), any())).thenReturn(Flux.just("ok"));
        var orchestrator = new AgentOrchestrator(memory, router, factory, toolBox, chat,
                new TraceService(new SimpleMeterRegistry()), new AgentProperties());

        orchestrator.open("u1", "s1", "推荐一部手机").chunks().blockLast();

        verify(factory).forIntent(isNull(), any(), any(), any());
    }
}
