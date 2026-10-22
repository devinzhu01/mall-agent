package com.mall.agent.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmTest {

    @Test
    void extractJsonStripsCodeFencesAndChatter() {
        String raw = "好的,结果如下:\n```json\n{\"a\": 1}\n```\n希望有帮助";
        assertThat(Llm.extractJson(raw)).isEqualTo("{\"a\": 1}");
    }

    @Test
    void extractJsonFailsWithoutObject() {
        assertThatThrownBy(() -> Llm.extractJson("没有json")).isInstanceOf(IllegalArgumentException.class);
    }
}
