package com.mall.agent.rag;

import java.util.List;

/** refused=true 表示知识库里没有足够相关的内容,调用方必须如实告知用户,不能编造。 */
public record RetrievalResult(List<Hit> hits, boolean refused, boolean rerankSkipped, List<String> queries) {
}
