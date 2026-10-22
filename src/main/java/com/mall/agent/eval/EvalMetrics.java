package com.mall.agent.eval;

import java.util.List;

/** 评测的纯计算指标。 */
final class EvalMetrics {

    private EvalMetrics() {
    }

    /** 期望命中的文档里,有多少出现在前 k 个检索结果中。 */
    static double recallAtK(List<String> expected, List<String> retrieved, int k) {
        if (expected.isEmpty()) {
            return 0;
        }
        List<String> topK = retrieved.subList(0, Math.min(k, retrieved.size()));
        long found = expected.stream().filter(topK::contains).count();
        return (double) found / expected.size();
    }

    /** 第一个相关结果排名的倒数(排名从 1 开始),没有相关结果为 0。 */
    static double mrr(List<String> expected, List<String> retrieved) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (expected.contains(retrieved.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0;
    }

    static double accuracy(int correct, int total) {
        return total == 0 ? 0 : (double) correct / total;
    }

    static double average(List<Double> values) {
        return values.isEmpty() ? 0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }
}
