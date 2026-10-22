package com.mall.agent.tool;

import com.mall.agent.memory.ChatMessage;
import com.mall.agent.rag.Hit;
import com.mall.agent.rag.RetrievalResult;
import com.mall.agent.rag.RetrievalService;
import com.mall.agent.skill.SkillRegistry;
import com.mall.agent.trace.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeToolsTest {

    private final RetrievalService retrieval = mock(RetrievalService.class);
    private final ToolContext ctx = new ToolContext(Map.of(ToolKeys.USER_ID, "u1001"));

    private KnowledgeTools tools(String category) {
        return new KnowledgeTools(retrieval, () -> List.<ChatMessage>of(), () -> category, Tracer.NOOP);
    }

    @Test
    void searchKnowledgeReturnsRefusalPayloadWhenRefused() {
        when(retrieval.retrieve(any(), any(), any(), any()))
                .thenReturn(new RetrievalResult(List.of(), true, false, List.of("q")));
        String json = tools(null).searchKnowledge("火星人怎么退货", ctx);
        assertThat(json).contains("\"ok\":true").contains("\"answerable\":false").contains("知识库中没有相关信息");
    }

    @Test
    void searchKnowledgePassagesCarrySource() {
        Hit hit = new Hit("c1", "return-policy", 2, "退换货政策 > 七日无理由退货 > 例外情况", "条款全文", "条款全文", 0.9);
        when(retrieval.retrieve(eq("能退吗"), any(), eq("售后"), any()))
                .thenReturn(new RetrievalResult(List.of(hit), false, false, List.of("q")));
        String json = tools("售后").searchKnowledge("能退吗", ctx);
        assertThat(json).contains("\"answerable\":true").contains("退换货政策 > 七日无理由退货 > 例外情况").contains("条款全文");
    }

    @Test
    void blankQueryIsInvalidArgument() {
        assertThatThrownBy(() -> tools(null).searchKnowledge("  ", ctx))
                .isInstanceOfSatisfying(ToolException.class, e -> assertThat(e.code()).isEqualTo("INVALID_ARG"));
    }

    @Test
    void loadSkillReturnsBodyAndDeniesOthers() {
        SkillRegistry registry = new SkillRegistry(List.of(
                "---\nname: a\ndescription: da\n---\n正文A", "---\nname: b\ndescription: db\n---\n正文B"));
        SkillTools skillTools = new SkillTools(registry, Set.of("a"));
        assertThat(skillTools.loadSkill("a", ctx)).contains("正文A");
        assertThatThrownBy(() -> skillTools.loadSkill("b", ctx))
                .isInstanceOfSatisfying(ToolException.class, e -> {
                    assertThat(e.code()).isEqualTo("NOT_FOUND");
                    assertThat(e.hint()).contains("a");
                });
    }
}
