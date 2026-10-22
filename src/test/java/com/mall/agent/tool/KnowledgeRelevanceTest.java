package com.mall.agent.tool;

import com.mall.agent.memory.ChatMessage;
import com.mall.agent.rag.Hit;
import com.mall.agent.rag.RetrievalResult;
import com.mall.agent.rag.RetrievalService;
import com.mall.agent.trace.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 重排服务不可用时分数不可信:必须告诉模型"相关性未经验证",否则无关问题也会被当成可回答。 */
class KnowledgeRelevanceTest {

    private final RetrievalService retrieval = mock(RetrievalService.class);
    private final ToolContext ctx = new ToolContext(Map.of(ToolKeys.USER_ID, "u1001"));
    private final KnowledgeTools tools = new KnowledgeTools(retrieval, () -> List.<ChatMessage>of(), () -> null, Tracer.NOOP);

    @Test
    void flagsUnverifiedRelevanceWhenRerankSkipped() {
        Hit hit = new Hit("c1", "delivery-guide", 1, "配送与签收指南 > 配送范围", "支持中国大陆配送", "支持中国大陆配送", 0.02);
        when(retrieval.retrieve(any(), any(), any(), any()))
                .thenReturn(new RetrievalResult(List.of(hit), false, true, List.of("q")));
        assertThat(tools.searchKnowledge("火星配送吗", ctx)).contains("\"relevanceVerified\":false").contains("没有相关信息");
    }

    @Test
    void marksRelevanceVerifiedNormally() {
        Hit hit = new Hit("c1", "faq", 1, "FAQ", "答案", "答案", 0.9);
        when(retrieval.retrieve(any(), any(), any(), any()))
                .thenReturn(new RetrievalResult(List.of(hit), false, false, List.of("q")));
        assertThat(tools.searchKnowledge("x", ctx)).contains("\"relevanceVerified\":true");
    }
}
