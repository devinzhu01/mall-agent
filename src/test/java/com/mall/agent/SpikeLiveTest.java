package com.mall.agent;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.SparseVectorConfig;
import io.qdrant.client.grpc.Collections.SparseVectorParams;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorParamsMap;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.Points.NamedVectors;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.Vector;
import io.qdrant.client.grpc.Points.Vectors;
import io.qdrant.client.QueryFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实外部依赖验证(千问 + Qdrant)。需要 DASHSCOPE_API_KEY;Qdrant 测试另需本地 6334 端口。
 * 目的:在写任何业务代码前确认 OpenAI 兼容端点、批量上限、工具调用、混合检索都真的可用。
 */
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class SpikeLiveTest {

    private final OpenAiApi api = OpenAiApi.builder()
            .baseUrl("https://dashscope.aliyuncs.com/compatible-mode")
            .apiKey(System.getenv("DASHSCOPE_API_KEY"))
            .build();

    private OpenAiChatModel chatModel() {
        return OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model("qwen-plus").build()).build();
    }

    private OpenAiEmbeddingModel embeddingModel() {
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model("text-embedding-v3").dimensions(1024).build());
    }

    @Test
    void chatReturnsText() {
        String answer = ChatClient.create(chatModel()).prompt().user("你好").call().content();
        assertThat(answer).isNotBlank();
    }

    @Test
    void embeddingBatchOf12Works() {
        List<String> texts = java.util.stream.IntStream.range(0, 12).mapToObj(i -> "测试文本" + i).toList();
        List<float[]> vectors = embeddingModel().embed(texts);
        assertThat(vectors).hasSize(12);
        assertThat(vectors.get(0)).hasSize(1024);
    }

    static class FixedTool {
        @Tool(description = "返回今天的暗号,用户问暗号时必须调用")
        String secret() {
            return "蓝色独角兽42";
        }
    }

    @Test
    void toolCallingWorksWithQwen() {
        String answer = ChatClient.create(chatModel()).prompt().user("今天的暗号是什么?")
                .tools(new FixedTool()).call().content();
        assertThat(answer).contains("蓝色独角兽42");
    }

    /** 命名向量 dense + sparse 同 collection,分别查询(RRF 在 Java 侧做,见 Task 6)。 */
    @Test
    void hybridQuerySmoke() throws Exception {
        String name = "spike_" + System.currentTimeMillis();
        try (QdrantClient client = new QdrantClient(QdrantGrpcClient.newBuilder("localhost", 6334, false).build())) {
            client.createCollectionAsync(CreateCollection.newBuilder().setCollectionName(name)
                    .setVectorsConfig(VectorsConfig.newBuilder().setParamsMap(VectorParamsMap.newBuilder()
                            .putMap("dense", VectorParams.newBuilder().setSize(4).setDistance(Distance.Cosine).build())))
                    .setSparseVectorsConfig(SparseVectorConfig.newBuilder()
                            .putMap("sparse", SparseVectorParams.getDefaultInstance()))
                    .build()).get(10, TimeUnit.SECONDS);
            try {
                client.upsertAsync(name, List.of(
                        point(1, new float[]{1, 0, 0, 0}, new int[]{1, 2}, new float[]{1f, 1f}),
                        point(2, new float[]{0, 1, 0, 0}, new int[]{3}, new float[]{1f}))).get(10, TimeUnit.SECONDS);

                var dense = client.queryAsync(QueryPoints.newBuilder().setCollectionName(name).setUsing("dense")
                        .setQuery(QueryFactory.nearest(new float[]{1, 0, 0, 0})).setLimit(2).build()).get(10, TimeUnit.SECONDS);
                var sparse = client.queryAsync(QueryPoints.newBuilder().setCollectionName(name).setUsing("sparse")
                        .setQuery(QueryFactory.nearest(List.of(3f), List.of(3))).setLimit(2).build()).get(10, TimeUnit.SECONDS);

                assertThat(dense.get(0).getId().getNum()).isEqualTo(1);
                assertThat(sparse.get(0).getId().getNum()).isEqualTo(2);
            } finally {
                client.deleteCollectionAsync(name).get(10, TimeUnit.SECONDS);
            }
        }
    }

    private static PointStruct point(long id, float[] dense, int[] sparseIdx, float[] sparseVal) {
        List<Integer> idx = java.util.stream.IntStream.of(sparseIdx).boxed().toList();
        List<Float> val = new java.util.ArrayList<>();
        for (float v : sparseVal) val.add(v);
        return PointStruct.newBuilder().setId(PointIdFactory.id(id))
                .setVectors(Vectors.newBuilder().setVectors(NamedVectors.newBuilder()
                        .putVectors("dense", io.qdrant.client.VectorFactory.vector(dense))
                        .putVectors("sparse", io.qdrant.client.VectorFactory.vector(val, idx))))
                .putPayload("k", ValueFactory.value("v")).build();
    }
}
