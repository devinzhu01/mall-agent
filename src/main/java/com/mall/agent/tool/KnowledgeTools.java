package com.mall.agent.tool;

import com.mall.agent.memory.ChatMessage;
import com.mall.agent.rag.RetrievalResult;
import com.mall.agent.rag.RetrievalService;
import com.mall.agent.trace.Tracer;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 知识库专家的检索工具。history/category 由本次请求提供(每个请求构造一个实例)。 */
public class KnowledgeTools {

    private final RetrievalService retrieval;
    private final Supplier<List<ChatMessage>> history;
    private final Supplier<String> category;
    private final Tracer tracer;

    public KnowledgeTools(RetrievalService retrieval, Supplier<List<ChatMessage>> history,
                          Supplier<String> category, Tracer tracer) {
        this.retrieval = retrieval;
        this.history = history;
        this.category = category;
        this.tracer = tracer;
    }

    @Tool(description = "在知识库中检索平台规则:退换货、退款时效、发票、配送运费、会员积分、优惠券、支付账户、活动规则、尺码与选购指南。"
            + "用户问'规则是什么/能不能/怎么办/多久'这类政策问题时使用;查具体订单或商品请用订单、商品工具。")
    public String searchKnowledge(@ToolParam(description = "检索问题,用完整的一句话描述用户想知道什么") String query, ToolContext ctx) {
        if (query == null || query.isBlank()) {
            throw new ToolException("INVALID_ARG", "检索问题不能为空", true, "请根据用户的问题补全检索语句");
        }
        RetrievalResult r = retrieval.retrieve(query, history.get(), category.get(), tracer);
        Map<String, Object> data = new LinkedHashMap<>();
        if (r.refused()) {
            data.put("answerable", false);
            data.put("message", "知识库中没有相关信息");
        } else {
            data.put("answerable", true);
            data.put("passages", r.hits().stream()
                    .map(h -> Map.of("source", h.titlePath(), "text", h.text())).toList());
        }
        return ToolResult.ok(data).toJson();
    }
}
