package com.mall.agent.agent;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.data.OrderRepo;
import com.mall.agent.data.Preference;
import com.mall.agent.data.ProductRepo;
import com.mall.agent.memory.MemoryContext;
import com.mall.agent.rag.RetrievalService;
import com.mall.agent.skill.SkillRegistry;
import com.mall.agent.tool.KnowledgeTools;
import com.mall.agent.tool.LogisticsClient;
import com.mall.agent.tool.LogisticsTools;
import com.mall.agent.tool.OrderTools;
import com.mall.agent.tool.ProductTools;
import com.mall.agent.tool.SkillTools;
import com.mall.agent.trace.Tracer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ExpertFactoryTest {

    AgentProperties props = new AgentProperties();
    ExpertFactory factory = new ExpertFactory(new SkillRegistry(), mock(ProductRepo.class), mock(OrderRepo.class),
            mock(LogisticsClient.class), mock(RetrievalService.class), props);
    MemoryContext ctx = new MemoryContext("用户之前问过羽绒服", List.of(),
            List.of(new Preference("brand", "华为", 0.9), new Preference("budget", "2000-3000", 0.8)));

    private static boolean has(Expert e, Class<?> type) {
        return e.toolBeans().stream().anyMatch(type::isInstance);
    }

    @Test
    void recommendExpertGetsPreferencesAndNoOrderTools() {
        Expert e = factory.forIntent(Intent.RECOMMEND, ctx, null, Tracer.NOOP);
        assertThat(e.systemPrompt()).contains("brand=华为").contains("budget=2000-3000").contains("product-recommendation");
        assertThat(has(e, ProductTools.class)).isTrue();
        assertThat(has(e, OrderTools.class)).isFalse();
        assertThat(has(e, LogisticsTools.class)).isFalse();
        assertThat(e.skills()).containsExactly("product-recommendation");
    }

    @Test
    void orderExpertHasNoProductToolsAndNoPreferences() {
        Expert e = factory.forIntent(Intent.ORDER, ctx, null, Tracer.NOOP);
        assertThat(has(e, OrderTools.class)).isTrue();
        assertThat(has(e, LogisticsTools.class)).isTrue();
        assertThat(has(e, ProductTools.class)).isFalse();
        assertThat(e.systemPrompt()).doesNotContain("brand=华为");
        assertThat(e.systemPrompt()).contains("order-troubleshooting").doesNotContain("product-recommendation");
    }

    @Test
    void knowledgeExpertHasKnowledgeToolOnly() {
        Expert e = factory.forIntent(Intent.KNOWLEDGE, ctx, "售后", Tracer.NOOP);
        assertThat(has(e, KnowledgeTools.class)).isTrue();
        assertThat(has(e, OrderTools.class)).isFalse();
        assertThat(has(e, ProductTools.class)).isFalse();
    }

    @Test
    void everyExpertCanLoadItsOwnSkillsViaSkillTools() {
        for (Intent i : List.of(Intent.RECOMMEND, Intent.ORDER, Intent.KNOWLEDGE)) {
            assertThat(has(factory.forIntent(i, ctx, null, Tracer.NOOP), SkillTools.class)).as(i.name()).isTrue();
        }
    }

    @Test
    void conversationSummaryIsInjectedIntoPrompt() {
        assertThat(factory.forIntent(Intent.KNOWLEDGE, ctx, null, Tracer.NOOP).systemPrompt())
                .contains("用户之前问过羽绒服");
    }

    @Test
    void everyPromptCarriesGroundingRules() {
        for (Intent i : List.of(Intent.RECOMMEND, Intent.ORDER, Intent.KNOWLEDGE)) {
            assertThat(factory.forIntent(i, ctx, null, Tracer.NOOP).systemPrompt())
                    .contains("不要编造").contains("answerable=false");
        }
    }

    @Test
    void singleModeMergesAllToolsAndSkills() {
        props.setMode("single");
        Expert e = factory.forIntent(Intent.KNOWLEDGE, ctx, null, Tracer.NOOP);
        assertThat(has(e, ProductTools.class) && has(e, OrderTools.class) && has(e, LogisticsTools.class)
                && has(e, KnowledgeTools.class) && has(e, SkillTools.class)).isTrue();
        assertThat(e.skills()).containsExactlyInAnyOrder("product-recommendation", "return-and-refund", "order-troubleshooting");
    }
}
