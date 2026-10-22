package com.mall.agent.agent;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.data.OrderRepo;
import com.mall.agent.data.ProductRepo;
import com.mall.agent.memory.MemoryContext;
import com.mall.agent.rag.RetrievalService;
import com.mall.agent.skill.SkillRegistry;
import com.mall.agent.tool.KnowledgeTools;
import com.mall.agent.tool.LogisticsClient;
import com.mall.agent.tool.LogisticsTools;
import com.mall.agent.tool.OrderTools;
import com.mall.agent.tool.ProductTools;
import com.mall.agent.trace.Tracer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 路由失败(如千问 429)时不能退化成"只读知识库专家":推荐类问题会因为没有商品工具而答不了。 */
class ExpertFallbackTest {

    ExpertFactory factory = new ExpertFactory(new SkillRegistry(), mock(ProductRepo.class), mock(OrderRepo.class),
            mock(LogisticsClient.class), mock(RetrievalService.class), new AgentProperties());
    MemoryContext ctx = new MemoryContext("", List.of(), List.of());

    private static boolean has(Expert e, Class<?> type) {
        return e.toolBeans().stream().anyMatch(type::isInstance);
    }

    @Test
    void nullIntentGivesAllToolsExpertEvenInMultiMode() {
        Expert e = factory.forIntent(null, ctx, null, Tracer.NOOP);
        assertThat(has(e, ProductTools.class) && has(e, OrderTools.class) && has(e, LogisticsTools.class)
                && has(e, KnowledgeTools.class)).isTrue();
    }

    @Test
    void everyPromptRequiresSearchingBeforeClaimingNoKnowledge() {
        for (Intent i : List.of(Intent.RECOMMEND, Intent.ORDER, Intent.KNOWLEDGE)) {
            assertThat(factory.forIntent(i, ctx, null, Tracer.NOOP).systemPrompt())
                    .contains("必须先调用 searchKnowledge").contains("没有调用过工具");
        }
    }
}
