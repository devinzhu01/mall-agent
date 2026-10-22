package com.mall.agent.eval;

import com.mall.agent.rag.KbSyncRunner;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

import static org.assertj.core.api.Assertions.assertThat;

/** EvalRunner 必须在 KbSyncRunner 之后执行,否则全新 Qdrant 上评测会在知识库同步前就跑完并退出。 */
class RunnerOrderTest {

    @Test
    void kbSyncRunsBeforeEval() {
        Order sync = KbSyncRunner.class.getAnnotation(Order.class);
        Order eval = EvalRunner.class.getAnnotation(Order.class);
        assertThat(sync).isNotNull();
        assertThat(eval).isNotNull();
        assertThat(sync.value()).isLessThan(eval.value());
    }
}
