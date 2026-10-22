package com.mall.agent.memory;

import com.mall.agent.data.Preference;
import com.mall.agent.data.PreferenceRepo;
import com.mall.agent.llm.Llm;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class PreferenceExtractorTest {

    Llm llm = mock(Llm.class);
    PreferenceRepo repo = mock(PreferenceRepo.class);
    PreferenceExtractor extractor = new PreferenceExtractor(llm, repo);

    private void llmReturns(PreferenceExtractor.Extracted e) {
        when(llm.json(any(), any(), any(), any(), any(), eq(PreferenceExtractor.Extracted.class))).thenReturn(e);
    }

    @Test
    void extractorDropsLowConfidenceAndUnknownKeys() {
        llmReturns(new PreferenceExtractor.Extracted(List.of(
                new PreferenceExtractor.Item("brand", "华为", 0.9),
                new PreferenceExtractor.Item("brand", "小米", 0.3),
                new PreferenceExtractor.Item("password", "123456", 0.99),
                new PreferenceExtractor.Item("budget", " ", 0.9))));

        extractor.extractAndSave("u1", "我喜欢华为", "好的");

        ArgumentCaptor<Preference> saved = ArgumentCaptor.forClass(Preference.class);
        verify(repo, times(1)).upsert(eq("u1"), saved.capture());
        assertThat(saved.getValue()).isEqualTo(new Preference("brand", "华为", 0.9));
    }

    @Test
    void extractorUpsertsAllAccepted() {
        llmReturns(new PreferenceExtractor.Extracted(List.of(
                new PreferenceExtractor.Item("brand", "华为", 0.9),
                new PreferenceExtractor.Item("budget", "2000-3000", 0.7))));
        extractor.extractAndSave("u1", "预算两三千,想要华为", "好的");
        verify(repo, times(2)).upsert(eq("u1"), any());
    }

    @Test
    void extractorSwallowsLlmFailure() {
        when(llm.json(any(), any(), any(), any(), any(), eq(PreferenceExtractor.Extracted.class)))
                .thenThrow(new IllegalStateException("bad json"));
        extractor.extractAndSave("u1", "hi", "hello");
        verify(repo, never()).upsert(any(), any());
    }

    @Test
    void extractorHandlesNullItems() {
        llmReturns(new PreferenceExtractor.Extracted(null));
        extractor.extractAndSave("u1", "hi", "hello");
        verify(repo, never()).upsert(any(), any());
    }
}
