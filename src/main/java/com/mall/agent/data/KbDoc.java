package com.mall.agent.data;

public record KbDoc(String docId, int version, String title, String category, String fileName,
                    String contentHash, int chunkCount, boolean effective) {
}
