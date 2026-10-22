package com.mall.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import io.qdrant.client.QdrantClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cfg;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.sql.init.mode=embedded", "spring.ai.openai.api-key=test-key",
        "agent.rag.sync-on-startup=false"})
class ConfigGuardsTest {

    @MockitoBean QdrantClient qdrant;
    @Autowired Environment env;

    @Test
    void asyncRequestTimeoutAllowsLongToolLoops() {
        // 流式回答含工具循环 + 检索 + 重排,可能超过 Tomcat 默认的 30 秒异步超时
        assertThat(env.getProperty("spring.mvc.async.request-timeout", Duration.class))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void adminTokenHasNoWellKnownDefault() {
        assertThat(env.getProperty("agent.admin-token", "")).isNotEqualTo("change-me");
    }
}
