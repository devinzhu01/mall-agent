package com.mall.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RrfTest {

    private static Hit hit(String id) {
        return new Hit(id, "d", 1, "t", "text-" + id, "parent-" + id, 0);
    }

    @Test
    void rrfPromotesItemsRankedWellInBothLists() {
        // A 在两个列表都是第 2;B、C 各在一个列表第 1 → A=2/62 > B=C=1/61
        var l1 = List.of(hit("B"), hit("A"));
        var l2 = List.of(hit("C"), hit("A"));
        List<Hit> fused = Rrf.fuse(List.of(l1, l2), 60, 10);
        assertThat(fused).extracting(Hit::chunkId).containsExactly("A", "B", "C");
        assertThat(fused.get(0).score()).isEqualTo(2.0 / 62, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void rrfMergesDuplicatesByChunkId() {
        List<Hit> fused = Rrf.fuse(List.of(List.of(hit("A"), hit("B")), List.of(hit("A"))), 60, 10);
        assertThat(fused).extracting(Hit::chunkId).containsExactly("A", "B");
    }

    @Test
    void rrfRespectsLimit() {
        List<Hit> fused = Rrf.fuse(List.of(List.of(hit("A"), hit("B"), hit("C"))), 60, 2);
        assertThat(fused).hasSize(2);
    }

    @Test
    void rrfOfEmptyListsIsEmpty() {
        assertThat(Rrf.fuse(List.of(List.of(), List.of()), 60, 5)).isEmpty();
    }
}
