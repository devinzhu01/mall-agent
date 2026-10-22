package com.mall.agent.rag;

import java.util.List;

/** 解析后的文档:按标题结构拆成若干 Section,还没有切块。 */
public record ParsedDoc(String docId, int version, String title, String category, List<Section> sections) {

    /** titlePath 是从文档标题到当前小节的标题链;isTable/isFaq 决定后面怎么切。 */
    public record Section(List<String> titlePath, String text, boolean isTable, boolean isFaq) {
    }
}
