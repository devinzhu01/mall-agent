package com.mall.agent.rag;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.memory.ChatMessage;
import com.mall.agent.trace.Tracer;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索链路总装:改写 → 稠密+稀疏双路召回 → RRF 融合 → 元数据过滤 → 重排 → 阈值拒答 → 父块展开。
 * 每一步都由 agent.rag.* 开关控制,评测里据此做消融对比(只稠密 / +稀疏 / +改写 / +重排)。
 */
@Service
public class RetrievalService {

    private static final int RRF_K = 60;

    private final QueryRewriter rewriter;
    private final KbStore store;
    private final EmbeddingModel embedding;
    private final SparseEncoder sparseEncoder;
    private final Reranker reranker;
    private final AgentProperties props;

    public RetrievalService(QueryRewriter rewriter, KbStore store, EmbeddingModel embedding,
                            SparseEncoder sparseEncoder, Reranker reranker, AgentProperties props) {
        this.rewriter = rewriter;
        this.store = store;
        this.embedding = embedding;
        this.sparseEncoder = sparseEncoder;
        this.reranker = reranker;
        this.props = props;
    }

    public RetrievalResult retrieve(String query, List<ChatMessage> history, String category, Tracer tracer) {
        AgentProperties.Rag cfg = props.getRag();

        List<String> queries = cfg.isRewriteEnabled() ? rewriter.rewrite(query, history, tracer) : List.of(query);
        tracer.step("rag.queries", String.join(" | ", queries));

        KbFilter filter = new KbFilter(true, category);
        List<float[]> vectors = embedding.embed(queries);
        List<List<Hit>> recalls = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            recalls.add(store.denseSearch(vectors.get(i), filter, cfg.getDenseTopN()));
            if (cfg.isHybridEnabled() && cfg.isSparseEnabled()) {
                recalls.add(store.sparseSearch(sparseEncoder.encode(queries.get(i)), filter, cfg.getSparseTopN()));
            }
        }
        List<Hit> fused = Rrf.fuse(recalls, RRF_K, Math.max(cfg.getDenseTopN(), cfg.getSparseTopN()));
        tracer.step("rag.recall", recalls.size() + " lists -> " + fused.size() + " fused candidates");
        if (fused.isEmpty()) {
            return new RetrievalResult(List.of(), true, false, queries);
        }

        // 重排用改写后的独立问题(queries 的第一条),它已经消除了指代
        List<Hit> top;
        boolean skipped = false;
        if (cfg.isRerankEnabled()) {
            Reranker.Reranked rr = reranker.rerank(queries.get(0), fused, cfg.getRerankTopK());
            skipped = rr.skipped();
            top = rr.hits();
            tracer.step("rag.rerank", top.isEmpty() ? "no hits"
                    : "top score=" + top.get(0).score() + (skipped ? " (rerank skipped)" : ""));
            if (!skipped && (top.isEmpty() || top.get(0).score() < cfg.getRefuseThreshold())) {
                return new RetrievalResult(List.of(), true, false, queries);
            }
        } else {
            top = fused.subList(0, Math.min(cfg.getRerankTopK(), fused.size()));
        }
        return new RetrievalResult(expandToParents(top), false, skipped, queries);
    }

    public RetrievalResult retrieve(String query, List<ChatMessage> history, String category) {
        return retrieve(query, history, category, Tracer.NOOP);
    }

    /** 子块命中后换成父块文本;多个子块指向同一父块时只保留分数最高的一个。 */
    private static List<Hit> expandToParents(List<Hit> hits) {
        Map<String, Hit> byParent = new LinkedHashMap<>();
        for (Hit h : hits) {
            byParent.putIfAbsent(h.parentText(), h.expanded());
        }
        return new ArrayList<>(byParent.values());
    }
}
