package com.mall.agent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.mall.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 检索链路第 4 步:重排。召回阶段(向量/关键词)追求"别漏掉",分数粗糙;
 * 重排用 cross-encoder 模型把 query 和每个候选放在一起打分,精度高但慢,所以只对融合后的少量候选做。
 * 调用失败时降级为 RRF 顺序截断,并标记 skipped,让上层知道分数不可用于拒答判断。
 */
@Component
public class Reranker {

    public record Reranked(List<Hit> hits, boolean skipped) {
    }

    private static final Logger log = LoggerFactory.getLogger(Reranker.class);

    /** (query, 候选文本列表) → 与候选一一对应的相关性分数 */
    private final BiFunction<String, List<String>, double[]> scorer;

    @Autowired
    public Reranker(AgentProperties props, @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this(restScorer(props.getRag(), apiKey));
    }

    Reranker(BiFunction<String, List<String>, double[]> scorer) {
        this.scorer = scorer;
    }

    public Reranked rerank(String query, List<Hit> candidates, int topK) {
        if (candidates.isEmpty()) {
            return new Reranked(List.of(), false);
        }
        try {
            List<String> docs = candidates.stream().map(h -> h.titlePath() + "\n" + h.text()).toList();
            double[] scores = scorer.apply(query, docs);
            List<Hit> ranked = new java.util.ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                ranked.add(candidates.get(i).withScore(scores[i]));
            }
            ranked.sort((a, b) -> Double.compare(b.score(), a.score()));
            return new Reranked(ranked.subList(0, Math.min(topK, ranked.size())), false);
        } catch (Exception e) {
            log.warn("rerank failed, keeping RRF order: {}", e.toString());
            return new Reranked(candidates.subList(0, Math.min(topK, candidates.size())), true);
        }
    }

    private static BiFunction<String, List<String>, double[]> restScorer(AgentProperties.Rag cfg, String apiKey) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(8000);
        RestClient client = RestClient.builder().requestFactory(factory).build();
        return (query, docs) -> {
            JsonNode resp = client.post().uri(cfg.getRerankUrl())
                    .header("Authorization", "Bearer " + apiKey)
                    .body(Map.of("model", cfg.getRerankModel(),
                            "input", Map.of("query", query, "documents", docs),
                            "parameters", Map.of("return_documents", false, "top_n", docs.size())))
                    .retrieve().body(JsonNode.class);
            double[] scores = new double[docs.size()];
            for (JsonNode r : resp.path("output").path("results")) {
                scores[r.path("index").asInt()] = r.path("relevance_score").asDouble();
            }
            return scores;
        };
    }
}
