package com.mall.agent.rag;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocParserTest {

    @TempDir Path dir;
    private final DocParser parser = new DocParser();

    private Path md(String name, String content) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @Test
    void fileNameParsesDocIdAndVersion() throws Exception {
        ParsedDoc doc = parser.parse(md("return-policy__v2.md", "# 退换货政策\n\n正文"), "售后");
        assertThat(doc.docId()).isEqualTo("return-policy");
        assertThat(doc.version()).isEqualTo(2);
        assertThat(doc.category()).isEqualTo("售后");
        assertThat(doc.title()).isEqualTo("退换货政策");
    }

    @Test
    void markdownSplitsByHeadingWithTitlePath() throws Exception {
        String text = """
                # 售后政策
                ## 七日无理由
                自签收起7天内可申请无理由退货。
                ### 例外情况
                定制类、贴身类商品不支持无理由退货。
                """;
        ParsedDoc doc = parser.parse(md("p__v1.md", text), "售后");
        assertThat(doc.sections()).extracting(s -> String.join(" > ", s.titlePath()))
                .contains("售后政策 > 七日无理由", "售后政策 > 七日无理由 > 例外情况");
        ParsedDoc.Section ex = doc.sections().stream()
                .filter(s -> s.titlePath().contains("例外情况")).findFirst().orElseThrow();
        assertThat(ex.text()).contains("定制类");
    }

    @Test
    void faqPairsAreMarkedAsFaqSections() throws Exception {
        String text = """
                # 常见问题
                Q: 能退吗?
                A: 签收7天内可退。
                Q: 运费谁出?
                A: 质量问题商家承担。
                """;
        ParsedDoc doc = parser.parse(md("faq__v1.md", text), "FAQ");
        List<ParsedDoc.Section> faqs = doc.sections().stream().filter(ParsedDoc.Section::isFaq).toList();
        assertThat(faqs).hasSize(2);
        assertThat(faqs.get(0).text()).contains("能退吗").contains("7天内可退");
        assertThat(faqs.get(1).text()).contains("运费谁出");
    }

    @Test
    void markdownTableBecomesTableSection() throws Exception {
        String text = """
                # 运费
                ## 运费表
                | 地区 | 运费 |
                |---|---|
                | 华东 | 6元 |
                """;
        ParsedDoc doc = parser.parse(md("fee__v1.md", text), "物流");
        assertThat(doc.sections()).anyMatch(s -> s.isTable() && s.text().contains("华东"));
    }

    @Test
    void docxHeadingsAndTableAreExtracted() throws Exception {
        Path p = dir.resolve("size-guide__v1.docx");
        try (XWPFDocument d = new XWPFDocument(); OutputStream out = Files.newOutputStream(p)) {
            XWPFParagraph h1 = d.createParagraph();
            h1.setStyle("Heading1");
            h1.createRun().setText("尺码指南");
            XWPFParagraph h2 = d.createParagraph();
            h2.setStyle("Heading2");
            h2.createRun().setText("上衣尺码对照");
            XWPFTable t = d.createTable(2, 2);
            t.getRow(0).getCell(0).setText("尺码");
            t.getRow(0).getCell(1).setText("身高");
            t.getRow(1).getCell(0).setText("M");
            t.getRow(1).getCell(1).setText("170-175");
            d.write(out);
        }
        ParsedDoc doc = parser.parse(p, "选购指南");
        assertThat(doc.title()).isEqualTo("尺码指南");
        ParsedDoc.Section table = doc.sections().stream().filter(ParsedDoc.Section::isTable).findFirst().orElseThrow();
        assertThat(table.titlePath()).containsExactly("尺码指南", "上衣尺码对照");
        assertThat(table.text()).contains("|").contains("尺码").contains("170-175");
    }
}
