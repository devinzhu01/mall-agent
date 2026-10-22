package com.mall.agent.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion:多路召回的排名融合。score = Σ 1/(k + rank),rank 从 1 开始。
 * 只看名次不看原始分数,所以稠密(余弦)和稀疏(词频)两种量纲不同的分数可以直接合并。
 */
public final class Rrf {

    private Rrf() {
    }

    public static List<Hit> fuse(List<List<Hit>> rankedLists, int k, int limit) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Hit> hits = new LinkedHashMap<>();
        for (List<Hit> list : rankedLists) {
            for (int i = 0; i < list.size(); i++) {
                Hit h = list.get(i);
                scores.merge(h.chunkId(), 1.0 / (k + i + 1), Double::sum);
                hits.putIfAbsent(h.chunkId(), h);
            }
        }
        List<Hit> fused = new ArrayList<>();
        scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())   // 稳定排序:同分保持首次出现的顺序
                .limit(limit)
                .forEach(e -> fused.add(hits.get(e.getKey()).withScore(e.getValue())));
        return fused;
    }
}
