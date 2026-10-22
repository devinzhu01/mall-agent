package com.mall.agent.eval;

import java.util.List;
import java.util.Map;

/**
 * 一次评测的完整结果。metrics 是扁平的 "<section>.<指标>" 或 "<section>.<配置>.<指标>" → 数值,
 * 这样两次报告可以逐项对比。
 */
public record EvalReport(String timestamp, String model, Map<String, Double> metrics, List<Failure> failures) {

    public record Failure(String section, String query, String expected, String actual) {
    }
}
