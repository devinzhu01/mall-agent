package com.mall.agent.rag;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.memory.ChatMessage;
import com.mall.agent.trace.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {

    QueryRewriter rewriter = mock(QueryRewriter.class);
    KbStore store = mock(KbStore.class);
    EmbeddingModel embedding = mock(EmbeddingModel.class);
    Reranker reranker = mock(Reranker.class);
    AgentProperties props = new AgentProperties();
    RetrievalService service;

    private static Hit hit(String id, String parent) {
        return new Hit(id, "return-policy", 2, "政策 > " + id, "child-" + id, parent, 0.02);
    }

    @BeforeEach
    void setUp() {
        when(embedding.embed(anyList())).thenAnswer(inv -> {
            List<String> in = inv.getArgument(0);
            List<float[]> out = new ArrayList<>();
            in.forEach(s -> out.add(new float[]{1}));
            return out;
        });
        when(rewriter.rewrite(anyString(), anyList(), any())).thenReturn(List.of("q1", "q2"));
        when(store.denseSearch(any(), any(), anyInt())).thenReturn(List.of(hit("A", "PA"), hit("B", "PB")));
        when(store.sparseSearch(any(), any(), anyInt())).thenReturn(List.of(hit("B", "PB"), hit("C", "PC")));
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            List<Hit> in = inv.getArgument(1);
            return new Reranker.Reranked(in.stream().map(h -> h.withScore(0.8)).toList(), false);
        });
        service = new RetrievalService(rewriter, store, embedding, new SparseEncoder(), reranker, props);
    }

    private RetrievalResult retrieve() {
        return service.retrieve("能退吗", List.<ChatMessage>of(), null, Tracer.NOOP);
    }

    @Test
    void retrieveRunsDenseAndSparseForEachQuery() {
        retrieve();
        verify(store, times(2)).denseSearch(any(), any(), anyInt());
        verify(store, times(2)).sparseSearch(any(), any(), anyInt());
    }

    @Test
    void sparseDisabledSkipsSparseSearch() {
        props.getRag().setSparseEnabled(false);
        retrieve();
        verify(store, never()).sparseSearch(any(), any(), anyInt());
    }

    @Test
    void hybridDisabledMeansDenseOnly() {
        props.getRag().setHybridEnabled(false);
        retrieve();
        verify(store, never()).sparseSearch(any(), any(), anyInt());
    }

    @Test
    void rewriteDisabledUsesOriginalQueryOnly() {
        props.getRag().setRewriteEnabled(false);
        retrieve();
        verify(rewriter, never()).rewrite(anyString(), anyList(), any());
        verify(store, times(1)).denseSearch(any(), any(), anyInt());
    }

    @Test
    void lowRerankScoreRefuses() {
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            List<Hit> in = inv.getArgument(1);
            return new Reranker.Reranked(in.stream().map(h -> h.withScore(0.1)).toList(), false);
        });
        RetrievalResult r = retrieve();
        assertThat(r.refused()).isTrue();
        assertThat(r.hits()).isEmpty();
    }

    @Test
    void emptyKnowledgeBaseRefuses() {
        when(store.denseSearch(any(), any(), anyInt())).thenReturn(List.of());
        when(store.sparseSearch(any(), any(), anyInt())).thenReturn(List.of());
        RetrievalResult r = retrieve();
        assertThat(r.refused()).isTrue();
        verify(reranker, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    void rerankSkippedKeepsRrfResultsWithoutThresholdCheck() {
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            List<Hit> in = inv.getArgument(1);
            return new Reranker.Reranked(in.subList(0, 2), true);
        });
        RetrievalResult r = retrieve();
        assertThat(r.refused()).isFalse();
        assertThat(r.rerankSkipped()).isTrue();
        assertThat(r.hits()).isNotEmpty();
    }

    @Test
    void hitsExpandToParentTextAndDedupe() {
        when(store.denseSearch(any(), any(), anyInt())).thenReturn(List.of(hit("A1", "SAME"), hit("A2", "SAME")));
        when(store.sparseSearch(any(), any(), anyInt())).thenReturn(List.of());
        RetrievalResult r = retrieve();
        assertThat(r.hits()).hasSize(1);
        assertThat(r.hits().get(0).text()).isEqualTo("SAME");
    }

    @Test
    void categoryAndEffectiveFilterPassedToStore() {
        service.retrieve("能退吗", List.of(), "售后", Tracer.NOOP);
        verify(store, times(2)).denseSearch(any(), eq(new KbFilter(true, "售后")), anyInt());
    }

    @Test
    void rrfFusesBothRecallListsBeforeRerank() {
        retrieve();
        // 两路召回的并集 A、B、C 都应该进入重排候选
        verify(reranker).rerank(anyString(), org.mockito.ArgumentMatchers.argThat(
                (List<Hit> l) -> l.stream().map(Hit::chunkId).toList().containsAll(List.of("A", "B", "C"))), anyInt());
    }

    @Test
    void categoryFilterRetriesWithoutCategoryWhenNothingFound() {
        when(store.denseSearch(any(), eq(new KbFilter(true, "物流")), anyInt())).thenReturn(List.of());
        when(store.sparseSearch(any(), eq(new KbFilter(true, "物流")), anyInt())).thenReturn(List.of());

        RetrievalResult r = service.retrieve("发票怎么开", List.of(), "物流", Tracer.NOOP);

        assertThat(r.refused()).isFalse();
        verify(store, times(2)).denseSearch(any(), eq(new KbFilter(true, null)), anyInt());
        verify(rewriter, times(1)).rewrite(anyString(), anyList(), any());   // 改写只做一次
    }
}
