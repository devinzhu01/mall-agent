package com.mall.agent.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillRegistryTest {

    private static final String A = "---\nname: return-and-refund\ndescription: 退换货办理流程\n---\n步骤一:确认订单状态";
    private static final String B = "---\nname: product-recommendation\ndescription: 推荐话术\n---\n先问预算";

    private final SkillRegistry registry = new SkillRegistry(List.of(A, B));

    @Test
    void catalogListsOnlyAllowedSkills() {
        String catalog = registry.catalog(Set.of("return-and-refund"));
        assertThat(catalog).contains("- return-and-refund: 退换货办理流程").doesNotContain("product-recommendation");
    }

    @Test
    void loadReturnsBodyForAllowed() {
        assertThat(registry.load("return-and-refund", Set.of("return-and-refund")))
                .get().extracting(Skill::body).isEqualTo("步骤一:确认订单状态");
    }

    @Test
    void loadDeniedForOtherExpertsSkill() {
        assertThat(registry.load("product-recommendation", Set.of("return-and-refund"))).isEmpty();
    }

    @Test
    void loadUnknownIsEmpty() {
        assertThat(registry.load("nope", Set.of("nope"))).isEmpty();
    }

    @Test
    void missingFrontmatterFailsStartup() {
        assertThatThrownBy(() -> new SkillRegistry(List.of("没有 frontmatter 的正文")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SkillRegistry(List.of("---\nname: x\n---\n缺 description")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bundledSkillsAreLoadedFromClasspath() {
        SkillRegistry real = new SkillRegistry();
        Set<String> all = Set.of("return-and-refund", "order-troubleshooting", "product-recommendation");
        for (String name : all) {
            assertThat(real.load(name, all)).as(name).isPresent();
        }
    }
}
