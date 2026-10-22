package com.mall.agent;

import com.mall.agent.agent.AgentOrchestrator;
import com.mall.agent.skill.SkillRegistry;
import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/** 整个应用的依赖注入冒烟测试:H2 代替 MySQL,Qdrant 客户端 Mock,不连任何外部服务。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ctx;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.sql.init.mode=embedded",
        "spring.ai.openai.api-key=test-key",
        "agent.rag.sync-on-startup=false"})
class ApplicationContextTest {

    @MockitoBean QdrantClient qdrant;
    @Autowired ApplicationContext context;

    @Test
    void contextLoadsWithAllAgentBeans() {
        assertThat(context.getBean(AgentOrchestrator.class)).isNotNull();
        assertThat(context.getBean(SkillRegistry.class)).isNotNull();
    }
}
