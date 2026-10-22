package com.mall.agent.rag;

import com.mall.agent.config.AgentProperties;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QueryFactory;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.SparseVectorConfig;
import io.qdrant.client.grpc.Collections.SparseVectorParams;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorParamsMap;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Qdrant 存取。一个 collection 里同时放两路向量:dense(语义)和 sparse(关键词),
 * 检索时分别查询、在 Java 侧用 RRF 融合,这样每一步都能单独开关、单独评测。
 * 不用 Spring AI 的 QdrantVectorStore:它只支持单个稠密向量。
 */
@Component
public class KbStore {

    private static final int TIMEOUT_SECONDS = 15;

    private final QdrantClient client;
    private final AgentProperties props;
    private final SparseEncoder sparseEncoder;

    public KbStore(QdrantClient client, AgentProperties props, SparseEncoder sparseEncoder) {
        this.client = client;
        this.props = props;
        this.sparseEncoder = sparseEncoder;
    }

    public String collection() {
        return props.getRag().getCollection();
    }

    public void ensureCollection() {
        if (await(client.collectionExistsAsync(collection()))) {
            return;
        }
        await(client.createCollectionAsync(CreateCollection.newBuilder().setCollectionName(collection())
                .setVectorsConfig(VectorsConfig.newBuilder().setParamsMap(VectorParamsMap.newBuilder()
                        .putMap("dense", VectorParams.newBuilder().setSize(props.getRag().getDenseDim())
                                .setDistance(Distance.Cosine).build())))
                .setSparseVectorsConfig(SparseVectorConfig.newBuilder()
                        .putMap("sparse", SparseVectorParams.getDefaultInstance()))
                .build()));
    }

    public void upsert(List<Chunk> chunks, List<float[]> dense) {
        List<PointStruct> points = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            SparseVec sv = sparseEncoder.encode(c.titlePath() + " " + c.text());
            Map<String, Value> payload = new HashMap<>();
            payload.put("chunkId", ValueFactory.value(c.id()));
            payload.put("docId", ValueFactory.value(c.docId()));
            payload.put("version", ValueFactory.value(c.version()));
            payload.put("category", ValueFactory.value(c.category()));
            payload.put("titlePath", ValueFactory.value(c.titlePath()));
            payload.put("text", ValueFactory.value(c.text()));
            payload.put("parentText", ValueFactory.value(c.parentText()));
            payload.put("effective", ValueFactory.value(c.effective()));
            payload.put("contentHash", ValueFactory.value(c.contentHash()));
            points.add(PointStruct.newBuilder()
                    .setId(PointIdFactory.id(UUID.fromString(c.id())))
                    .setVectors(VectorsFactory.namedVectors(Map.of(
                            "dense", VectorFactory.vector(dense.get(i)),
                            "sparse", VectorFactory.vector(toList(sv.values()), toList(sv.indices())))))
                    .putAllPayload(payload).build());
        }
        if (!points.isEmpty()) {
            await(client.upsertAsync(collection(), points));
        }
    }

    public void deleteByDocId(String docId) {
        await(client.deleteAsync(collection(), docFilter(docId, null)));
    }

    public void deleteByDocVersion(String docId, int version) {
        await(client.deleteAsync(collection(), docFilter(docId, version)));
    }

    /** 版本切换时只改标记,不删数据:旧版本保留可回溯,检索默认过滤。 */
    public void setEffective(String docId, int version, boolean effective) {
        await(client.setPayloadAsync(collection(), Map.of("effective", ValueFactory.value(effective)),
                docFilter(docId, version), true, null, null));
    }

    public List<Hit> denseSearch(float[] query, KbFilter f, int topN) {
        return search(QueryPoints.newBuilder().setUsing("dense").setQuery(QueryFactory.nearest(query)), f, topN);
    }

    public List<Hit> sparseSearch(SparseVec query, KbFilter f, int topN) {
        if (query.indices().length == 0) {
            return List.of();
        }
        return search(QueryPoints.newBuilder().setUsing("sparse")
                .setQuery(QueryFactory.nearest(toList(query.values()), toList(query.indices()))), f, topN);
    }

    private List<Hit> search(QueryPoints.Builder q, KbFilter f, int topN) {
        q.setCollectionName(collection()).setLimit(topN)
                .setWithPayload(WithPayloadSelectorFactory.enable(true));
        Filter filter = searchFilter(f);
        if (filter != null) {
            q.setFilter(filter);
        }
        List<ScoredPoint> points = await(client.queryAsync(q.build()));
        return points.stream().map(KbStore::toHit).toList();
    }

    private static Filter searchFilter(KbFilter f) {
        Filter.Builder b = Filter.newBuilder();
        boolean any = false;
        if (f.onlyEffective()) {
            b.addMust(ConditionFactory.match("effective", true));
            any = true;
        }
        if (f.category() != null && !f.category().isBlank()) {
            b.addMust(ConditionFactory.matchKeyword("category", f.category()));
            any = true;
        }
        return any ? b.build() : null;
    }

    private static Filter docFilter(String docId, Integer version) {
        Filter.Builder b = Filter.newBuilder().addMust(ConditionFactory.matchKeyword("docId", docId));
        if (version != null) {
            b.addMust(ConditionFactory.match("version", version.longValue()));
        }
        return b.build();
    }

    private static Hit toHit(ScoredPoint p) {
        Map<String, Value> m = p.getPayloadMap();
        return new Hit(m.get("chunkId").getStringValue(), m.get("docId").getStringValue(),
                (int) m.get("version").getIntegerValue(), m.get("titlePath").getStringValue(),
                m.get("text").getStringValue(), m.get("parentText").getStringValue(), p.getScore());
    }

    private static List<Float> toList(float[] a) {
        List<Float> l = new ArrayList<>(a.length);
        for (float v : a) l.add(v);
        return l;
    }

    private static List<Integer> toList(int[] a) {
        List<Integer> l = new ArrayList<>(a.length);
        for (int v : a) l.add(v);
        return l;
    }

    private static <T> T await(com.google.common.util.concurrent.ListenableFuture<T> f) {
        try {
            return f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant call interrupted", e);
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("Qdrant call failed: " + e.getMessage(), e);
        }
    }

}
