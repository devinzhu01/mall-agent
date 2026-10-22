package com.mall.agent.rag;

import com.mall.agent.llm.Llm;
import com.mall.agent.memory.ChatMessage;
import com.mall.agent.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索链路第 1 步:Query 改写。解决两类"检索不准":
 *  1) 指代/省略("它能退吗" → "羽绒服能退吗"),靠对话历史做指代消解;
 *  2) 口语 vs 书面语("能退吗" → "是否支持七日无理由退货"),并扩展 2~3 个不同角度的问法,提升召回。
 * 改写失败时降级为原问题,不影响主流程。
 */
@Component
public class QueryRewriter {

    public record Rewrite(String standalone, List<String> variants) {
    }

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);
    private static final int MAX_QUERIES = 4;
    private static final int HISTORY_TURNS = 4;

    private static final String SYSTEM = """
            你是电商客服知识库的检索改写助手。
            1. 结合对话历史,把用户最新问题改写成一个独立完整的检索问题(消除"它/这个/那个"等指代,补全省略内容);
            2. 把口语改成规则文档里常见的书面术语,例如"能退吗"→"是否支持七日无理由退货","啥时候到账"→"退款到账时间";
            3. 再给出2到3个不同角度的扩展检索语句。
            只输出 JSON,不要解释,格式:{"standalone":"...","variants":["...","..."]}""";

    private final Llm llm;

    public QueryRewriter(Llm llm) {
        this.llm = llm;
    }

    public List<String> rewrite(String query, List<ChatMessage> history, Tracer tracer) {
        try {
            Rewrite r = llm.json(tracer, "rewrite", null, SYSTEM, userPrompt(query, history), Rewrite.class);
            Set<String> queries = new LinkedHashSet<>();
            if (r.standalone() != null && !r.standalone().isBlank()) {
                queries.add(r.standalone().trim());
            }
            if (r.variants() != null) {
                r.variants().stream().filter(v -> v != null && !v.isBlank()).map(String::trim).forEach(queries::add);
            }
            if (queries.isEmpty()) {
                return List.of(query);
            }
            return new ArrayList<>(queries).subList(0, Math.min(MAX_QUERIES, queries.size()));
        } catch (Exception e) {
            log.warn("query rewrite failed, using original query: {}", e.toString());
            return List.of(query);
        }
    }

    private static String userPrompt(String query, List<ChatMessage> history) {
        StringBuilder sb = new StringBuilder("对话历史:\n");
        int from = Math.max(0, history.size() - HISTORY_TURNS);
        for (ChatMessage m : history.subList(from, history.size())) {
            sb.append(m.role()).append(": ").append(m.content()).append('\n');
        }
        return sb.append("\n用户最新问题:").append(query).toString();
    }
}
