package com.mall.agent.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/** 把 EvalReport 渲染成 Markdown;有上一次报告时每个指标附带差值,方便看"改一处 → 指标变化"。 */
final class ReportWriter {

    private ReportWriter() {
    }

    static String toMarkdown(EvalReport report, Optional<EvalReport> previous) {
        StringBuilder md = new StringBuilder("# 评测报告 ").append(report.timestamp()).append("\n\n");
        md.append("模型:").append(report.model());
        previous.ifPresent(p -> md.append("  ·  对比上次:").append(p.timestamp()));
        md.append("\n");

        Map<String, Map<String, Double>> bySection = new LinkedHashMap<>();
        report.metrics().forEach((key, v) -> bySection
                .computeIfAbsent(key.substring(0, key.indexOf('.')), s -> new LinkedHashMap<>())
                .put(key.substring(key.indexOf('.') + 1), v));

        bySection.forEach((section, metrics) -> {
            md.append("\n## ").append(section).append("\n\n");
            boolean pivot = metrics.keySet().stream().allMatch(k -> k.indexOf('.') > 0);
            if (pivot) {
                pivotTable(md, section, metrics, report, previous);
            } else {
                simpleTable(md, section, metrics, previous);
            }
        });

        if (!report.failures().isEmpty()) {
            md.append("\n## 失败用例\n\n| 模块 | 问题 | 期望 | 实际 |\n|---|---|---|---|\n");
            for (EvalReport.Failure f : report.failures()) {
                md.append("| ").append(cell(f.section())).append(" | ").append(cell(f.query())).append(" | ")
                        .append(cell(f.expected())).append(" | ").append(cell(f.actual())).append(" |\n");
            }
        }
        return md.toString();
    }

    private static void simpleTable(StringBuilder md, String section, Map<String, Double> metrics,
                                    Optional<EvalReport> previous) {
        md.append("| 指标 | 数值 |").append(previous.isPresent() ? " Δ |\n|---|---|---|\n" : "\n|---|---|\n");
        metrics.forEach((name, v) -> {
            md.append("| ").append(name).append(" | ").append(fmt(v)).append(" |");
            previous.ifPresent(p -> md.append(' ').append(delta(v, p.metrics().get(section + "." + name))).append(" |"));
            md.append('\n');
        });
    }

    /** "<配置>.<指标>" 形式的指标(如检索消融)转成 行=配置、列=指标 的矩阵。 */
    private static void pivotTable(StringBuilder md, String section, Map<String, Double> metrics,
                                   EvalReport report, Optional<EvalReport> previous) {
        List<String> configs = new ArrayList<>(new java.util.LinkedHashSet<>(
                metrics.keySet().stream().map(k -> k.substring(0, k.indexOf('.'))).toList()));
        TreeSet<String> columns = new TreeSet<>(metrics.keySet().stream().map(k -> k.substring(k.indexOf('.') + 1)).toList());
        md.append("| 配置 |");
        columns.forEach(c -> md.append(' ').append(c).append(" |"));
        md.append("\n|---|").append("---|".repeat(columns.size())).append('\n');
        for (String config : configs) {
            md.append("| ").append(config).append(" |");
            for (String col : columns) {
                Double v = metrics.get(config + "." + col);
                md.append(' ').append(v == null ? "-" : fmt(v));
                if (v != null) {
                    previous.ifPresent(p -> md.append(" (")
                            .append(delta(v, p.metrics().get(section + "." + config + "." + col))).append(")"));
                }
                md.append(" |");
            }
            md.append('\n');
        }
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }

    /** 与上次的差值;上次没有该指标时返回 "new"。 */
    private static String delta(double now, Double before) {
        if (before == null) {
            return "new";
        }
        double d = now - before;
        return (d >= 0 ? "+" : "") + String.format("%.3f", d);
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }
}
