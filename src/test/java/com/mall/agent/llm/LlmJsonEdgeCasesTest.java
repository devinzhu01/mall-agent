package com.mall.agent.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmJsonEdgeCasesTest {

    @Test
    void emptyArrayAnswerIsTreatedAsEmptyObject() {
        // 偏好抽取没有内容时,模型常直接返回 [] 而不是 {"items":[]}
        assertThat(Llm.extractJson("[]")).isEqualTo("{}");
        assertThat(Llm.extractJson("没有可抽取的偏好:[ ]")).isEqualTo("{}");
    }

    @Test
    void rateLimitErrorsAreRecognisedForRetry() {
        assertThat(Llm.isRateLimited(new RuntimeException("HTTP 429 - {\"code\":\"limit_requests\"}"))).isTrue();
        assertThat(Llm.isRateLimited(new RuntimeException("wrapped", new RuntimeException("HTTP 429 - x")))).isTrue();
        assertThat(Llm.isRateLimited(new RuntimeException("HTTP 401"))).isFalse();
    }
}
