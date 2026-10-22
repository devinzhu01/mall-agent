package com.mall.agent.rag;

import com.mall.agent.rag.ParsedDoc.Section;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 摄入链路第 1 步:文件 → ParsedDoc。
 * md 按 # 层级、docx 按 Heading 样式识别标题;表格单独成 Section(转 Markdown 文本);
 * FAQ 的每个 Q/A 单独成 Section。pdf 用 Tika 尽力提取文本,不保证表格结构。
 */
@Component
public class DocParser {

    private static final Pattern FILE_NAME = Pattern.compile("^(.+)__v(\\d+)\\.(md|docx|pdf)$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern QUESTION = Pattern.compile("^Q\\s*[:：]\\s*(.*)$");

    public ParsedDoc parse(Path file, String category) {
        Matcher m = FILE_NAME.matcher(file.getFileName().toString());
        if (!m.matches()) {
            throw new IllegalArgumentException("文件名需为 <docId>__v<N>.<md|docx|pdf>: " + file.getFileName());
        }
        String docId = m.group(1);
        int version = Integer.parseInt(m.group(2));
        Builder b = new Builder(docId);
        try {
            switch (m.group(3)) {
                case "md" -> parseMarkdown(Files.readString(file), b);
                case "docx" -> parseDocx(file, b);
                default -> parsePdf(file, b);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ParsedDoc(docId, version, b.title(), category, b.finish());
    }

    // ---------- markdown ----------

    private void parseMarkdown(String content, Builder b) {
        boolean inTable = false;
        for (String line : content.split("\\R")) {
            Matcher h = HEADING.matcher(line);
            Matcher q = QUESTION.matcher(line.trim());
            boolean tableLine = line.trim().startsWith("|");
            if (inTable && !tableLine) {
                b.endTable();
                inTable = false;
            }
            if (h.matches()) {
                b.heading(h.group(1).length(), h.group(2).trim());
            } else if (q.matches()) {
                b.startFaq(line.trim());
            } else if (tableLine) {
                if (!inTable) {
                    b.startTable();
                    inTable = true;
                }
                b.append(line.trim());
            } else {
                b.append(line);
            }
        }
        b.endTable();
    }

    // ---------- docx ----------

    private void parseDocx(Path file, Builder b) throws IOException {
        try (InputStream in = Files.newInputStream(file); XWPFDocument doc = new XWPFDocument(in)) {
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    int level = headingLevel(styleName(doc, p.getStyle()));
                    if (level > 0) {
                        b.heading(level, p.getText().trim());
                    } else {
                        b.append(p.getText());
                    }
                } else if (el instanceof XWPFTable t) {
                    b.startTable();
                    b.append(tableToMarkdown(t));
                    b.endTable();
                }
            }
        }
    }

    /** 样式 id 在中文 Word 里可能只是 "1"/"2",真正可靠的是样式名称(heading 1 / 标题 1);查不到名称时退回用 id。 */
    private static String styleName(XWPFDocument doc, String styleId) {
        if (styleId == null) return null;
        var style = doc.getStyles() == null ? null : doc.getStyles().getStyle(styleId);
        return style != null && style.getName() != null ? style.getName() : styleId;
    }

    private static int headingLevel(String style) {
        if (style == null) return 0;
        Matcher m = Pattern.compile("(?i)(?:heading|标题)\\s*(\\d)").matcher(style);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static String tableToMarkdown(XWPFTable t) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < t.getRows().size(); r++) {
            XWPFTableRow row = t.getRow(r);
            sb.append("| ");
            sb.append(String.join(" | ", row.getTableCells().stream().map(c -> c.getText().trim()).toList()));
            sb.append(" |\n");
            if (r == 0) {
                sb.append("|").append("---|".repeat(row.getTableCells().size())).append("\n");
            }
        }
        return sb.toString().trim();
    }

    // ---------- pdf ----------

    private void parsePdf(Path file, Builder b) {
        List<Document> docs = new TikaDocumentReader(new FileSystemResource(file)).get();
        for (Document d : docs) {
            for (String para : d.getText().split("\\n\\s*\\n")) {
                b.append(para.trim());
                b.append("");
            }
        }
    }

    /** 维护"当前标题链",把文本累积成 Section。 */
    private static class Builder {
        private final String fallbackTitle;
        private final List<String> path = new ArrayList<>();
        private final List<Section> sections = new ArrayList<>();
        private final StringBuilder buf = new StringBuilder();
        private String title;
        private boolean table;
        private boolean faq;
        private List<String> bufPath = List.of();

        Builder(String fallbackTitle) {
            this.fallbackTitle = fallbackTitle;
            this.bufPath = List.of(fallbackTitle);
        }

        String title() {
            return title != null ? title : fallbackTitle;
        }

        void heading(int level, String text) {
            flush();
            while (path.size() >= level) path.remove(path.size() - 1);
            while (path.size() < level - 1) path.add("");
            path.add(text);
            if (title == null && level == 1) title = text;
            bufPath = pathNoBlank();
        }

        void startFaq(String questionLine) {
            flush();
            faq = true;
            List<String> p = new ArrayList<>(pathNoBlank());
            p.add(questionLine.length() > 40 ? questionLine.substring(0, 40) : questionLine);
            bufPath = p;
            buf.append(questionLine).append('\n');
        }

        void startTable() {
            flush();
            table = true;
        }

        void endTable() {
            if (table) flush();
        }

        void append(String line) {
            if (line.isBlank() && buf.length() == 0) return;
            buf.append(line).append('\n');
        }

        private List<String> pathNoBlank() {
            List<String> p = path.stream().filter(s -> !s.isBlank()).toList();
            return p.isEmpty() ? List.of(fallbackTitle) : p;
        }

        private void flush() {
            String text = buf.toString().trim();
            if (!text.isEmpty()) {
                sections.add(new Section(List.copyOf(bufPath), text, table, faq));
            }
            buf.setLength(0);
            table = false;
            faq = false;
            bufPath = pathNoBlank();
        }

        List<Section> finish() {
            flush();
            return sections;
        }
    }
}
