package com.mall.agent.agent;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.memory.MemoryContext;
import com.mall.agent.memory.MemoryService;
import com.mall.agent.trace.TraceService;
import com.mall.agent.tool.ToolBox;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOrchestratorTest {

    MemoryService memory = mock(MemoryService.class);
    RouterAgent router = mock(RouterAgent.class);
    ExpertFactory factory = mock(ExpertFactory.class);
    ToolBox toolBox = mock(ToolBox.class);
    ExpertChat expertChat = mock(ExpertChat.class);
    AgentProperties props = new AgentProperties();
    AgentOrchestrator orchestrator;
    MemoryContext ctx = new MemoryContext("", List.of(), List.of());
    Expert expert = new Expert("order", "SYSTEM", List.of(), java.util.Set.of());

    @BeforeEach
    void setUp() {
        when(memory.load("u1001", "s1")).thenReturn(ctx);
        when(toolBox.build(anyList(), any())).thenReturn(new ToolCallback[0]);
        when(factory.forIntent(any(), any(), any(), any())).thenReturn(expert);
        orchestrator = new AgentOrchestrator(memory, router, factory, toolBox, expertChat,
                new TraceService(new SimpleMeterRegistry()), props);
    }

    private void routeTo(Intent intent, String reply) {
        when(router.route(any(), anyString(), any())).thenReturn(new RouteDecision(intent, null, reply));
    }

    @Test
    void chitchatReturnsRouterReplyWithoutExpert() {
        routeTo(Intent.CHITCHAT, "你好呀");
        var run = orchestrator.open("u1001", "s1", "你好");
        assertThat(run.chunks().collectList().block()).containsExactly("你好呀");
        verify(expertChat, never()).stream(anyString(), anyList(), anyString(), any(), anyString(), any());
        verify(memory, timeout(2000)).save(eq("u1001"), eq("s1"), eq("你好"), eq("你好呀"), any());
    }

    @Test
    void orchestratorSavesMemoryAfterStreamCompletes() {
        routeTo(Intent.ORDER, null);
        when(expertChat.stream(anyString(), anyList(), anyString(), any(), anyString(), any()))
                .thenReturn(Flux.just("订单", "已发货"));

        var run = orchestrator.open("u1001", "s1", "我的订单呢");
        assertThat(String.join("", run.chunks().collectList().block())).isEqualTo("订单已发货");

        verify(memory, timeout(2000)).save(eq("u1001"), eq("s1"), eq("我的订单呢"), eq("订单已发货"), any());
    }

    @Test
    void orchestratorPassesUserIdAndSystemPromptToExpertChat() {
        routeTo(Intent.ORDER, null);
        when(expertChat.stream(anyString(), anyList(), anyString(), any(), anyString(), any())).thenReturn(Flux.just("ok"));
        orchestrator.open("u1001", "s1", "查订单").chunks().blockLast();
        verify(expertChat).stream(eq("SYSTEM"), eq(List.of()), eq("查订单"), any(), eq("u1001"), any());
    }

    @Test
    void singleModeSkipsRouter() {
        props.setMode("single");
        when(expertChat.stream(anyString(), anyList(), anyString(), any(), anyString(), any())).thenReturn(Flux.just("ok"));
        orchestrator.open("u1001", "s1", "你好").chunks().blockLast();
        verify(router, never()).route(any(), anyString(), any());
    }

    @Test
    void expertFailureEmitsApologyAndDoesNotSaveMemory() {
        routeTo(Intent.ORDER, null);
        when(expertChat.stream(anyString(), anyList(), anyString(), any(), anyString(), any()))
                .thenReturn(Flux.error(new RuntimeException("model 429")));
        var run = orchestrator.open("u1001", "s1", "查订单");
        String text = String.join("", run.chunks().collectList().block());
        assertThat(text).contains("抱歉");
        verify(memory, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    void askCollectsTextTraceAndRoute() {
        routeTo(Intent.ORDER, null);
        when(expertChat.stream(anyString(), anyList(), anyString(), any(), anyString(), any())).thenReturn(Flux.just("a", "b"));
        var reply = orchestrator.ask("u1001", "s1", "查订单");
        assertThat(reply.text()).isEqualTo("ab");
        assertThat(reply.route().intent()).isEqualTo(Intent.ORDER);
        assertThat(reply.trace().traceId()).isNotBlank();
    }
}
