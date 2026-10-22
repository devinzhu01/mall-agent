package com.mall.agent.memory;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.llm.Llm;
import com.mall.agent.trace.Tracer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoryCompactorTest {

    Llm llm = mock(Llm.class);
    AgentProperties props = new AgentProperties();   // threshold 16, keepRecent 6, window 20
    HistoryCompactor compactor = new HistoryCompactor(llm, props);

    private static List<ChatMessage> messages(int n) {
        List<ChatMessage> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(i % 2 == 0 ? ChatMessage.user("u" + i) : ChatMessage.assistant("a" + i));
        }
        return list;
    }

    @Test
    void compactorLeavesShortHistoryUntouched() {
        var out = compactor.compact("旧摘要", messages(16), Tracer.NOOP);
        assertThat(out.summary()).isEqualTo("旧摘要");
        assertThat(out.recent()).hasSize(16);
        verify(llm, never()).text(any(), any(), any(), any(), any());
    }

    @Test
    void compactorKeepsRecentAndSummarizesOlder() {
        when(llm.text(any(), any(), any(), any(), any())).thenReturn("新摘要");
        List<ChatMessage> msgs = messages(20);

        var out = compactor.compact("旧摘要", msgs, Tracer.NOOP);

        assertThat(out.summary()).isEqualTo("新摘要");
        assertThat(out.recent()).isEqualTo(msgs.subList(14, 20));
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(llm).text(any(), eq("summarize"), any(), any(), user.capture());
        assertThat(user.getValue()).contains("旧摘要").contains("u0").contains("a13").doesNotContain("u14");
    }

    @Test
    void compactorPromptDemandsKeyFacts() {
        when(llm.text(any(), any(), any(), any(), any())).thenReturn("x");
        compactor.compact("", messages(20), Tracer.NOOP);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(llm).text(any(), any(), any(), system.capture(), any());
        assertThat(system.getValue()).contains("订单号").contains("商品").contains("决定");
    }

    @Test
    void compactorFallsBackToTruncationOnLlmFailure() {
        when(llm.text(any(), any(), any(), any(), any())).thenThrow(new RuntimeException("429"));
        List<ChatMessage> msgs = messages(30);

        var out = compactor.compact("旧摘要", msgs, Tracer.NOOP);

        assertThat(out.summary()).isEqualTo("旧摘要");
        assertThat(out.recent()).isEqualTo(msgs.subList(10, 30));   // 硬截断到 window=20 条
    }
}
