package com.mall.agent.eval;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReportWriterTest {

    private static EvalReport report(Map<String, Double> metrics, List<EvalReport.Failure> failures) {
        return new EvalReport("2026-10-22T10:00:00", "qwen-plus", metrics, failures);
    }

    @Test
    void reportShowsDeltaAgainstPrevious() {
        var prev = report(Map.of("routing.accuracy", 0.8), List.of());
        var now = report(Map.of("routing.accuracy", 0.9), List.of());
        String md = ReportWriter.toMarkdown(now, Optional.of(prev));
        assertThat(md).contains("routing").contains("0.900").contains("+0.100");
    }

    @Test
    void reportWithoutPreviousHasNoDeltaColumn() {
        String md = ReportWriter.toMarkdown(report(Map.of("routing.accuracy", 0.9), List.of()), Optional.empty());
        assertThat(md).contains("0.900").doesNotContain("Δ");
    }

    @Test
    void reportShowsNegativeDelta() {
        var prev = report(Map.of("routing.accuracy", 0.9), List.of());
        var now = report(Map.of("routing.accuracy", 0.85), List.of());
        assertThat(ReportWriter.toMarkdown(now, Optional.of(prev))).contains("-0.050");
    }

    @Test
    void ablationMetricsArePivotedByConfig() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("retrieval.dense-only.recall@4", 0.6);
        m.put("retrieval.dense-only.mrr", 0.5);
        m.put("retrieval.full.recall@4", 0.9);
        m.put("retrieval.full.mrr", 0.8);
        String md = ReportWriter.toMarkdown(report(m, List.of()), Optional.empty());
        assertThat(md).contains("| dense-only |").contains("| full |").contains("recall@4").contains("mrr");
    }

    @Test
    void reportListsFailedCases() {
        var failures = List.of(new EvalReport.Failure("routing", "你好吗", "CHITCHAT", "KNOWLEDGE"));
        String md = ReportWriter.toMarkdown(report(Map.of("routing.accuracy", 0.5), failures), Optional.empty());
        assertThat(md).contains("失败用例").contains("你好吗").contains("CHITCHAT").contains("KNOWLEDGE");
    }

    @Test
    void failureCellsEscapePipesAndNewlines() {
        var failures = List.of(new EvalReport.Failure("e2e", "a|b", "line1\nline2", "x"));
        String md = ReportWriter.toMarkdown(report(Map.of(), failures), Optional.empty());
        assertThat(md).contains("a\\|b").doesNotContain("line1\nline2");
    }
}
