package com.mall.agent.rag;

import com.mall.agent.config.AgentProperties;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真实 Qdrant 集成测试:本地 6334 端口没开时自动跳过。使用独立的临时 collection。 */
class KbStoreLiveTest {

    static QdrantClient client;
    static KbStore store;
    static final SparseEncoder ENC = new SparseEncoder();

    @BeforeAll
    static void setUp() throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", 6334), 300);
        } catch (Exception e) {
            assumeTrue(false, "Qdrant not running on localhost:6334");
        }
        AgentProperties props = new AgentProperties();
        props.getRag().setCollection("it_kb_" + System.currentTimeMillis());
        props.getRag().setDenseDim(4);
        client = new QdrantClient(QdrantGrpcClient.newBuilder("localhost", 6334, false).build());
        store = new KbStore(client, props, ENC);
        store.ensureCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (client != null) {
            client.deleteCollectionAsync(store.collection()).get();
            client.close();
        }
    }

    private static Chunk chunk(String id, String docId, int version, String text, boolean effective) {
        return new Chunk(id, docId, version, "售后", "政策 > " + text, text, "parent:" + text, effective, "h");
    }

    private static float[] vec(float a, float b, float c, float d) {
        return new float[]{a, b, c, d};
    }

    @Test
    void upsertThenDenseAndSparseSearch() {
        String id1 = java.util.UUID.nameUUIDFromBytes("i1".getBytes()).toString();
        String id2 = java.util.UUID.nameUUIDFromBytes("i2".getBytes()).toString();
        store.upsert(List.of(chunk(id1, "p-a", 1, "七日无理由退货", true), chunk(id2, "p-a", 1, "会员积分兑换", true)),
                List.of(vec(1, 0, 0, 0), vec(0, 1, 0, 0)));

        List<Hit> dense = store.denseSearch(vec(1, 0, 0, 0), new KbFilter(true, null), 5);
        List<Hit> sparse = store.sparseSearch(ENC.encode("无理由退货"), new KbFilter(true, null), 5);

        assertThat(dense.get(0).chunkId()).isEqualTo(id1);
        assertThat(sparse.get(0).chunkId()).isEqualTo(id1);
        assertThat(sparse.get(0).parentText()).isEqualTo("parent:七日无理由退货");
    }

    @Test
    void effectiveFilterExcludesOldVersion() {
        String v1 = java.util.UUID.nameUUIDFromBytes("v1".getBytes()).toString();
        String v2 = java.util.UUID.nameUUIDFromBytes("v2".getBytes()).toString();
        store.upsert(List.of(chunk(v1, "p-b", 1, "旧版退货规定七天", true), chunk(v2, "p-b", 2, "新版退货规定十五天", true)),
                List.of(vec(0, 0, 1, 0), vec(0, 0, 1, 0.1f)));
        store.setEffective("p-b", 1, false);

        List<String> dense = store.denseSearch(vec(0, 0, 1, 0), new KbFilter(true, null), 10)
                .stream().map(Hit::chunkId).toList();
        List<String> sparse = store.sparseSearch(ENC.encode("退货规定"), new KbFilter(true, null), 10)
                .stream().map(Hit::chunkId).toList();
        assertThat(dense).contains(v2).doesNotContain(v1);
        assertThat(sparse).contains(v2).doesNotContain(v1);
    }

    @Test
    void deleteByDocIdRemovesAllVersions() {
        String a = java.util.UUID.nameUUIDFromBytes("da".getBytes()).toString();
        String b = java.util.UUID.nameUUIDFromBytes("db".getBytes()).toString();
        store.upsert(List.of(chunk(a, "p-c", 1, "删除测试甲", true), chunk(b, "p-c", 2, "删除测试乙", true)),
                List.of(vec(0, 0, 0, 1), vec(0, 0.1f, 0, 1)));
        store.deleteByDocId("p-c");
        assertThat(store.denseSearch(vec(0, 0, 0, 1), new KbFilter(false, null), 10))
                .extracting(Hit::docId).doesNotContain("p-c");
    }
}
