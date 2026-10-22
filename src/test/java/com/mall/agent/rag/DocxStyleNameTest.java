package com.mall.agent.rag;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 中文 Word 的内置标题样式 id 是 "1"/"2",只有样式"名称"才是 heading 1:按名称识别标题。 */
class DocxStyleNameTest {

    @TempDir Path dir;

    @Test
    void headingDetectedByStyleNameWhenStyleIdIsNumeric() throws Exception {
        Path p = dir.resolve("guide__v1.docx");
        try (XWPFDocument d = new XWPFDocument(); OutputStream out = Files.newOutputStream(p)) {
            var styles = d.createStyles();
            for (int level = 1; level <= 2; level++) {
                CTStyle ct = CTStyle.Factory.newInstance();
                ct.setStyleId(String.valueOf(level));
                ct.addNewName().setVal("heading " + level);
                styles.addStyle(new XWPFStyle(ct));
            }
            XWPFParagraph h1 = d.createParagraph();
            h1.setStyle("1");
            h1.createRun().setText("退货指南");
            XWPFParagraph h2 = d.createParagraph();
            h2.setStyle("2");
            h2.createRun().setText("例外情况");
            d.createParagraph().createRun().setText("定制类不支持");
            d.write(out);
        }

        ParsedDoc doc = new DocParser().parse(p, "售后");

        assertThat(doc.title()).isEqualTo("退货指南");
        assertThat(doc.sections()).anyMatch(s ->
                s.titlePath().equals(List.of("退货指南", "例外情况")) && s.text().contains("定制类"));
    }
}
