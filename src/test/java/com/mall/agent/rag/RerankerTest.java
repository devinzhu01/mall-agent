package com.mall.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RerankerTest {

    private static Hit hit(String id) {
        return new Hit(id, "d", 1, "t", "text-" + id, "parent", 0.01);
    }

    @Test
    void rerankerReordersByRelevanceScore() {
        Reranker reranker = new Reranker((q, docs) -> new double[]{0.1, 0.9, 0.5});
        Reranker.Reranked out = reranker.rerank("q", List.of(hit("A"), hit("B"), hit("C")), 2);
        assertThat(out.skipped()).isFalse();
        assertThat(out.hits()).extracting(Hit::chunkId).containsExactly("B", "C");
        assertThat(out.hits().get(0).score()).isEqualTo(0.9);
    }

    @Test
    void rerankerFailureKeepsRrfOrder() {
        Reranker reranker = new Reranker((q, docs) -> { throw new IllegalStateException("429"); });
        Reranker.Reranked out = reranker.rerank("q", List.of(hit("A"), hit("B"), hit("C")), 2);
        assertThat(out.skipped()).isTrue();
        assertThat(out.hits()).extracting(Hit::chunkId).containsExactly("A", "B");
    }

    @Test
    void rerankerOfEmptyCandidatesIsEmptyAndNotSkipped() {
        Reranker reranker = new Reranker((q, docs) -> { throw new AssertionError("must not call"); });
        Reranker.Reranked out = reranker.rerank("q", List.of(), 3);
        assertThat(out.hits()).isEmpty();
        assertThat(out.skipped()).isFalse();
    }
}
