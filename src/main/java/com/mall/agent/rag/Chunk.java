package com.mall.agent.rag;

/**
 * 切块结果,同时也是写入 Qdrant 的 payload。
 * text 用于检索命中;parentText 是所在整个小节,命中后交给模型(子块检索、父块回答)。
 */
public record Chunk(String id, String docId, int version, String category, String titlePath,
                    String text, String parentText, boolean effective, String contentHash) {
}
