package com.mall.agent.rag;

import com.mall.agent.rag.ParsedDoc.Section;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 摄入链路第 2 步:ParsedDoc → Chunk。
 * 规则:FAQ 一问一答一块;表格整体一块(拆开就丢表头);长条款按句子切成子块,
 * 所有子块共享同一个 parentText。块 id 由 docId|version|序号 确定,重复摄入幂等。
 */
@Component
public class DocChunker {

    private static final String SENTENCE_END = "。！？；!?;\n";

    private final int childSize;

    public DocChunker() {
        this(300);
    }

    DocChunker(int childSize) {
        this.childSize = childSize;
    }

    public List<Chunk> chunk(ParsedDoc doc, String contentHash, boolean effective) {
        List<Chunk> chunks = new ArrayList<>();
        for (Section s : doc.sections()) {
            String path = String.join(" > ", s.titlePath());
            for (String piece : split(s)) {
                String id = UUID.nameUUIDFromBytes(
                        (doc.docId() + "|" + doc.version() + "|" + chunks.size()).getBytes(StandardCharsets.UTF_8)).toString();
                chunks.add(new Chunk(id, doc.docId(), doc.version(), doc.category(), path,
                        piece, s.text(), effective, contentHash));
            }
        }
        return chunks;
    }

    private List<String> split(Section s) {
        if (s.isFaq() || s.isTable() || s.text().length() <= childSize) {
            return List.of(s.text());
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String sentence : sentences(s.text())) {
            if (cur.length() > 0 && cur.length() + sentence.length() > childSize) {
                pieces.add(cur.toString().trim());
                cur.setLength(0);
            }
            // 单句本身超长时硬切,保证每块不超过 childSize
            String rest = sentence;
            while (rest.length() > childSize) {
                pieces.add(rest.substring(0, childSize));
                rest = rest.substring(childSize);
            }
            cur.append(rest);
        }
        if (cur.toString().trim().length() > 0) pieces.add(cur.toString().trim());
        return pieces;
    }

    private static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (char c : text.toCharArray()) {
            cur.append(c);
            if (SENTENCE_END.indexOf(c) >= 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
