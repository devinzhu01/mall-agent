package com.mall.agent.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 用真实的模拟知识库文件验证解析/切分,保证评测集所依赖的"难点样本"确实存在。 */
class KbSamplesTest {

    private static final Path KB = Path.of("src/main/resources/kb");
    private final DocParser parser = new DocParser();
    private final DocChunker chunker = new DocChunker();

    private ParsedDoc parse(String rel, String category) {
        return parser.parse(KB.resolve(rel), category);
    }

    @Test
    void returnPolicyV2KeepsExceptionClauseAndRefundTable() {
        ParsedDoc doc = parse("售后/return-policy__v2.docx", "售后");
        assertThat(doc.title()).isEqualTo("退换货政策(2026年修订版)");
        assertThat(doc.sections()).anyMatch(s -> String.join(">", s.titlePath()).endsWith("七日无理由退货>例外情况")
                && s.text().contains("定制类") && s.text().contains("入耳式耳机"));
        assertThat(doc.sections()).anyMatch(s -> s.isTable() && s.text().contains("信用卡") && s.text().contains("5-10个工作日"));
    }

    @Test
    void returnPolicyV1AndV2DifferOnRefundTime() {
        ParsedDoc v1 = parse("售后/return-policy__v1.md", "售后");
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.sections()).anyMatch(s -> s.text().contains("7个工作日"));
    }

    @Test
    void faqHasOneSectionPerQuestion() {
        ParsedDoc doc = parse("FAQ/faq__v1.md", "FAQ");
        assertThat(doc.sections().stream().filter(ParsedDoc.Section::isFaq).count()).isGreaterThanOrEqualTo(15);
        assertThat(chunker.chunk(doc, "h", true)).allMatch(c -> c.text().contains("A:"));
    }

    @Test
    void shippingFeeTableIsOneChunkWithHeader() {
        ParsedDoc doc = parse("物流/shipping-fee__v1.docx", "物流");
        var chunks = chunker.chunk(doc, "h", true).stream().filter(c -> c.text().contains("续重运费")).toList();
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("西南").contains("10元");
    }

    @Test
    void sizeGuideHasShirtAndShoeTables() {
        ParsedDoc doc = parse("选购指南/size-guide__v1.docx", "选购指南");
        assertThat(doc.sections().stream().filter(ParsedDoc.Section::isTable).count()).isEqualTo(2);
    }

    @Test
    void membershipMarkdownTableIsExtracted() {
        ParsedDoc doc = parse("会员营销/membership__v1.md", "会员营销");
        assertThat(doc.sections()).anyMatch(s -> s.isTable() && s.text().contains("钻石会员"));
    }

    @Test
    void promoPdfTextIsExtracted() {
        ParsedDoc doc = parse("活动规则/promo-2026__v1.pdf", "活动规则");
        String all = doc.sections().stream().map(ParsedDoc.Section::text).reduce("", String::concat);
        assertThat(all).contains("定金不予退还").contains("每满300元减40元");
    }
}
