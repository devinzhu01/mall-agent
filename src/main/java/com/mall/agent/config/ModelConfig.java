package com.mall.agent.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 对话/向量模型由 Spring AI 自动装配(yml 配置);这里只补 Qdrant 客户端。 */
@Configuration
public class ModelConfig {

    @Bean(destroyMethod = "close")
    QdrantClient qdrantClient(AgentProperties props) {
        var rag = props.getRag();
        return new QdrantClient(QdrantGrpcClient.newBuilder(rag.getQdrantHost(), rag.getQdrantPort(), false).build());
    }
}
