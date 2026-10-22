package com.mall.agent.rag;

import com.mall.agent.llm.Llm;
import com.mall.agent.memory.ChatMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryRewriterTest {

    private final Llm llm = mock(Llm.class);
    private final QueryRewriter rewriter = new QueryRewriter(llm);

    private void llmReturns(QueryRewriter.Rewrite r) {
        when(llm.json(any(), any(), any(), any(), any(), eq(QueryRewriter.Rewrite.class))).thenReturn(r);
    }

    @Test
    void rewriterFallsBackToOriginalOnLlmError() {
        when(llm.json(any(), any(), any(), any(), any(), eq(QueryRewriter.Rewrite.class)))
                .thenThrow(new IllegalStateException("bad json"));
        assertThat(rewriter.rewrite("能退吗", List.of(), com.mall.agent.trace.Tracer.NOOP)).containsExactly("能退吗");
    }

    @Test
    void rewriterDedupesAndCapsAtFour() {
        llmReturns(new QueryRewriter.Rewrite("是否支持七日无理由退货", List.of("是否支持七日无理由退货", "退货条件", "退货期限", "退货例外", "多余")));
        assertThat(rewriter.rewrite("能退吗", List.of(), com.mall.agent.trace.Tracer.NOOP))
                .containsExactly("是否支持七日无理由退货", "退货条件", "退货期限", "退货例外");
    }

    @Test
    void rewriterBlankStandaloneFallsBackToOriginal() {
        llmReturns(new QueryRewriter.Rewrite("  ", List.of()));
        assertThat(rewriter.rewrite("能退吗", List.of(), com.mall.agent.trace.Tracer.NOOP)).containsExactly("能退吗");
    }

    @Test
    void historyIsIncludedInPromptForCoreferenceResolution() {
        llmReturns(new QueryRewriter.Rewrite("羽绒服能退吗", List.of()));
        rewriter.rewrite("它能退吗", List.of(ChatMessage.user("我买了一件羽绒服"), ChatMessage.assistant("好的")),
                com.mall.agent.trace.Tracer.NOOP);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(llm).json(any(), any(), any(), any(), user.capture(), eq(QueryRewriter.Rewrite.class));
        assertThat(user.getValue()).contains("我买了一件羽绒服").contains("它能退吗");
    }
}
