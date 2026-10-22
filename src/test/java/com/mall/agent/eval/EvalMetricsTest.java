package com.mall.agent.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EvalMetricsTest {

    @Test
    void recallAtKCountsExpectedFoundInTopK() {
        List<String> expected = List.of("a", "b");
        List<String> retrieved = List.of("x", "a", "y", "b");
        assertThat(EvalMetrics.recallAtK(expected, retrieved, 2)).isEqualTo(0.5);
        assertThat(EvalMetrics.recallAtK(expected, retrieved, 4)).isEqualTo(1.0);
    }

    @Test
    void recallOfNothingRetrievedIsZero() {
        assertThat(EvalMetrics.recallAtK(List.of("a"), List.of(), 4)).isZero();
    }

    @Test
    void mrrUsesFirstRelevantRank() {
        assertThat(EvalMetrics.mrr(List.of("a", "b"), List.of("x", "b", "a"))).isEqualTo(0.5);
        assertThat(EvalMetrics.mrr(List.of("a"), List.of("a"))).isEqualTo(1.0);
    }

    @Test
    void mrrIsZeroWhenNothingRelevant() {
        assertThat(EvalMetrics.mrr(List.of("a"), List.of("x", "y"))).isZero();
    }

    @Test
    void accuracyHandlesZeroTotal() {
        assertThat(EvalMetrics.accuracy(3, 4)).isEqualTo(0.75);
        assertThat(EvalMetrics.accuracy(0, 0)).isZero();
    }

    @Test
    void averageOfEmptyIsZero() {
        assertThat(EvalMetrics.average(List.of())).isZero();
        assertThat(EvalMetrics.average(List.of(1.0, 2.0))).isCloseTo(1.5, within(1e-9));
    }
}
