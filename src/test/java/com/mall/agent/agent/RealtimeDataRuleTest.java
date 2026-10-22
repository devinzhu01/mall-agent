package com.mall.agent.agent;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.data.OrderRepo;
import com.mall.agent.data.ProductRepo;
import com.mall.agent.memory.MemoryContext;
import com.mall.agent.rag.RetrievalService;
import com.mall.agent.skill.SkillRegistry;
import com.mall.agent.tool.LogisticsClient;
import com.mall.agent.trace.Tracer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 订单状态、库存、价格是实时数据:历史对话里的旧结果不能直接当答案。 */
class RealtimeDataRuleTest {

    ExpertFactory factory = new ExpertFactory(new SkillRegistry(), mock(ProductRepo.class), mock(OrderRepo.class),
            mock(LogisticsClient.class), mock(RetrievalService.class), new AgentProperties());

    @Test
    void everyPromptForbidsReusingStaleOrderAndPriceDataFromHistory() {
        MemoryContext ctx = new MemoryContext("用户之前查过订单", List.of(), List.of());
        for (Intent i : List.of(Intent.RECOMMEND, Intent.ORDER, Intent.KNOWLEDGE)) {
            assertThat(factory.forIntent(i, ctx, null, Tracer.NOOP).systemPrompt())
                    .contains("实时数据").contains("重新调用");
        }
    }
}
