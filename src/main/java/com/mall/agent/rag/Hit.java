package com.mall.agent.rag;

/** 一个检索命中:text 是命中的子块,parentText 是所在整个小节(交给模型的是后者)。 */
public record Hit(String chunkId, String docId, int version, String titlePath,
                  String text, String parentText, double score) {

    public Hit withScore(double newScore) {
        return new Hit(chunkId, docId, version, titlePath, text, parentText, newScore);
    }
}
