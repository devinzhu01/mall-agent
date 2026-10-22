package com.mall.agent.agent;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.data.Preference;
import com.mall.agent.llm.Llm;
import com.mall.agent.memory.ChatMessage;
import com.mall.agent.memory.MemoryContext;
import com.mall.agent.trace.Tracer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouterAgentTest {

    Llm llm = mock(Llm.class);
    AgentProperties props = new AgentProperties();
    RouterAgent router = new RouterAgent(llm, props);
    MemoryContext emptyCtx = new MemoryContext("", List.of(), List.of());

    private void llmReturns(RouterAgent.Route r) {
        when(llm.json(any(), any(), any(), any(), any(), eq(RouterAgent.Route.class))).thenReturn(r);
    }

    @Test
    void routesToIntentAndCategory() {
        llmReturns(new RouterAgent.Route("KNOWLEDGE", "售后", null));
        RouteDecision d = router.route(emptyCtx, "能退吗", Tracer.NOOP);
        assertThat(d.intent()).isEqualTo(Intent.KNOWLEDGE);
        assertThat(d.category()).isEqualTo("售后");
    }

    @Test
    void routerFallsBackToKnowledgeOnError() {
        when(llm.json(any(), any(), any(), any(), any(), eq(RouterAgent.Route.class)))
                .thenThrow(new IllegalStateException("bad json"));
        RouteDecision d = router.route(emptyCtx, "???", Tracer.NOOP);
        assertThat(d.intent()).isEqualTo(Intent.KNOWLEDGE);
        assertThat(d.category()).isNull();
    }

    @Test
    void unknownIntentFallsBackToKnowledge() {
        llmReturns(new RouterAgent.Route("SOMETHING_ELSE", null, null));
        assertThat(router.route(emptyCtx, "x", Tracer.NOOP).intent()).isEqualTo(Intent.KNOWLEDGE);
    }

    @Test
    void unknownCategoryIsDropped() {
        llmReturns(new RouterAgent.Route("KNOWLEDGE", "火星", null));
        assertThat(router.route(emptyCtx, "x", Tracer.NOOP).category()).isNull();
    }

    @Test
    void chitchatKeepsReplyAndBlankReplyGetsDefault() {
        llmReturns(new RouterAgent.Route("CHITCHAT", null, "你好呀!"));
        assertThat(router.route(emptyCtx, "你好", Tracer.NOOP).reply()).isEqualTo("你好呀!");
        llmReturns(new RouterAgent.Route("CHITCHAT", null, " "));
        assertThat(router.route(emptyCtx, "你好", Tracer.NOOP).reply()).contains("商城助手");
    }

    @Test
    void routerUsesConfiguredRouterModel() {
        llmReturns(new RouterAgent.Route("ORDER", null, null));
        router.route(emptyCtx, "我的订单", Tracer.NOOP);
        verify(llm).json(any(), eq("route"), eq("qwen-turbo"), any(), any(), eq(RouterAgent.Route.class));
    }

    @Test
    void routerReceivesOnlyRecentMessagesAndSummary() {
        llmReturns(new RouterAgent.Route("ORDER", null, null));
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) history.add(ChatMessage.user("消息" + i));
        var ctx = new MemoryContext("用户在咨询羽绒服", history, List.of(new Preference("brand", "华为", 0.9)));

        router.route(ctx, "它到哪了", Tracer.NOOP);

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(llm).json(any(), any(), any(), any(), user.capture(), eq(RouterAgent.Route.class));
        assertThat(user.getValue()).contains("用户在咨询羽绒服").contains("消息9").contains("消息6")
                .doesNotContain("消息5").doesNotContain("brand").contains("它到哪了");
    }
}
