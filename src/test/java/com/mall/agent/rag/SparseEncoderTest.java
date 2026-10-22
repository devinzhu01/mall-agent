package com.mall.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SparseEncoderTest {

    private final SparseEncoder encoder = new SparseEncoder();

    private Set<Integer> indices(String text) {
        return java.util.Arrays.stream(encoder.encode(text).indices()).boxed().collect(Collectors.toSet());
    }

    @Test
    void chineseBigramsMatchAcrossPhrases() {
        Set<Integer> shared = new HashSet<>(indices("七日无理由退货"));
        shared.retainAll(indices("无理由退货"));
        assertThat(shared).isNotEmpty();
    }

    @Test
    void unrelatedTextsDoNotOverlap() {
        Set<Integer> shared = new HashSet<>(indices("七日无理由退货"));
        shared.retainAll(indices("会员积分兑换"));
        assertThat(shared).isEmpty();
    }

    @Test
    void sparseEncodingIsDeterministicAndSorted() {
        SparseVec a = encoder.encode("华为 nova12 手机 退货 退货");
        SparseVec b = encoder.encode("华为 nova12 手机 退货 退货");
        assertThat(a.indices()).isEqualTo(b.indices());
        assertThat(a.values()).isEqualTo(b.values());
        for (int i = 1; i < a.indices().length; i++) {
            assertThat(a.indices()[i]).isGreaterThan(a.indices()[i - 1]);
        }
    }

    @Test
    void latinWordsAreLowercasedAsWholeTokens() {
        assertThat(indices("NOVA12")).isEqualTo(indices("nova12")).hasSize(1);
    }

    @Test
    void repeatedTermsGetHigherButSublinearWeight() {
        SparseVec once = encoder.encode("退货");
        SparseVec thrice = encoder.encode("退货 退货 退货");
        assertThat(thrice.values()[0]).isGreaterThan(once.values()[0]).isLessThan(once.values()[0] * 3);
    }

    @Test
    void emptyTextGivesEmptyVector() {
        assertThat(encoder.encode("  ").indices()).isEmpty();
    }
}
