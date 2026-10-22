package com.mall.agent.data;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest(properties = {"spring.sql.init.mode=embedded", "spring.sql.init.encoding=UTF-8",
        "spring.datasource.url=jdbc:h2:mem:repo;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa",
        "spring.datasource.password="})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProductRepo.class, OrderRepo.class, PreferenceRepo.class, KbDocumentRepo.class, SummaryRepo.class})
class RepoTest {

    @Autowired ProductRepo products;
    @Autowired OrderRepo orders;
    @Autowired PreferenceRepo prefs;
    @Autowired KbDocumentRepo kbDocs;
    @Autowired SummaryRepo summaries;

    @Test
    void orderOfOtherUserIsInvisible() {
        assertThat(orders.findByNoAndUser("2026100100000001", "u1001")).isPresent();
        assertThat(orders.findByNoAndUser("2026100100000001", "u1002")).isEmpty();
    }

    @Test
    void orderViewCarriesItems() {
        OrderView o = orders.findByNoAndUser("2026100100000001", "u1001").orElseThrow();
        assertThat(o.items()).isNotEmpty();
        assertThat(o.status()).isEqualTo("SHIPPED");
    }

    @Test
    void listByUserFiltersByStatus() {
        assertThat(orders.listByUser("u1001", null, 10)).hasSizeGreaterThanOrEqualTo(3);
        assertThat(orders.listByUser("u1001", "DONE", 10)).allMatch(o -> o.status().equals("DONE"));
    }

    @Test
    void searchFiltersByPriceAndBrand() {
        List<Product> r = products.search(null, "手机", new BigDecimal("2000"), new BigDecimal("4000"), "华为", 10);
        assertThat(r).isNotEmpty().allMatch(p ->
                p.brand().equals("华为") && p.category().equals("手机")
                        && p.price().compareTo(new BigDecimal("2000")) >= 0
                        && p.price().compareTo(new BigDecimal("4000")) <= 0);
    }

    @Test
    void searchByKeywordMatchesName() {
        assertThat(products.search("羽绒服", null, null, null, null, 10)).isNotEmpty();
    }

    @Test
    void findByIdReturnsEmptyWhenMissing() {
        assertThat(products.findById(999999L)).isEqualTo(Optional.empty());
    }

    @Test
    void preferenceUpsertIsIdempotent() {
        prefs.upsert("u1001", new Preference("brand", "华为", 0.6));
        prefs.upsert("u1001", new Preference("brand", "华为", 0.9));
        List<Preference> list = prefs.list("u1001");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).confidence()).isEqualTo(0.9);
        prefs.deleteAll("u1001");
        assertThat(prefs.list("u1001")).isEmpty();
    }

    @Test
    void kbDocSaveUpserts() {
        kbDocs.save(new KbDoc("return-policy", 1, "退换货政策", "售后", "return-policy__v1.md", "h1", 5, true));
        kbDocs.save(new KbDoc("return-policy", 1, "退换货政策", "售后", "return-policy__v1.md", "h2", 7, false));
        KbDoc d = kbDocs.find("return-policy", 1).orElseThrow();
        assertThat(d.contentHash()).isEqualTo("h2");
        assertThat(d.chunkCount()).isEqualTo(7);
        assertThat(d.effective()).isFalse();
        assertThat(kbDocs.listByDocId("return-policy")).hasSize(1);
        kbDocs.setEffective("return-policy", 1, true);
        assertThat(kbDocs.find("return-policy", 1).orElseThrow().effective()).isTrue();
        kbDocs.delete("return-policy");
        assertThat(kbDocs.listByDocId("return-policy")).isEmpty();
    }

    @Test
    void summarySaveAndGet() {
        assertThat(summaries.get("s1")).isEmpty();
        summaries.save("s1", "u1001", "第一版摘要", 10);
        summaries.save("s1", "u1001", "第二版摘要", 20);
        assertThat(summaries.get("s1")).contains("第二版摘要");
        assertThat(summaries.coveredCount("s1")).isEqualTo(20);
        assertThat(summaries.coveredCount("none")).isZero();
    }
}
