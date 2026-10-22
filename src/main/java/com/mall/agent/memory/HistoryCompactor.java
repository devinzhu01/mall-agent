package com.mall.agent.memory;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.llm.Llm;
import com.mall.agent.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 历史过长怎么办:超过 summarize-threshold 条后,把"较早的消息 + 旧摘要"压成新摘要,
 * 只保留最近 keep-recent 条原文。摘要提示词强制保留订单号、商品、用户的决定等事实,
 * 否则压缩后模型会"忘记"用户前面说过的关键信息。压缩失败时退化为硬截断,保证请求不失败。
 */
@Component
public class HistoryCompactor {

    public record Compacted(String summary, List<ChatMessage> recent) {
    }

    private static final Logger log = LoggerFactory.getLogger(HistoryCompactor.class);

    private static final String SYSTEM = """
            你是客服对话摘要助手。把"已有摘要"和"新增对话"合并成一份新的摘要,供后续对话作为背景使用。
            必须保留:用户提到的订单号、商品名称/型号、用户已经做出的决定、尚未解决的问题、用户明确表达的偏好(品牌、预算、尺码)。
            可以丢弃:寒暄、重复内容、已经完全解决且无后续影响的细节。
            直接输出摘要正文,不超过300字,不要加标题或解释。""";

    private final Llm llm;
    private final AgentProperties props;

    public HistoryCompactor(Llm llm, AgentProperties props) {
        this.llm = llm;
        this.props = props;
    }

    public Compacted compact(String oldSummary, List<ChatMessage> messages, Tracer tracer) {
        AgentProperties.Memory cfg = props.getMemory();
        if (messages.size() <= cfg.getSummarizeThreshold()) {
            return new Compacted(oldSummary, messages);
        }
        int split = messages.size() - cfg.getKeepRecent();
        try {
            String summary = llm.text(tracer, "summarize", null, SYSTEM,
                    userPrompt(oldSummary, messages.subList(0, split)));
            return new Compacted(summary.trim(), messages.subList(split, messages.size()));
        } catch (Exception e) {
            log.warn("summarization failed, falling back to truncation: {}", e.toString());
            int from = Math.max(0, messages.size() - cfg.getWindowMessages());
            return new Compacted(oldSummary, messages.subList(from, messages.size()));
        }
    }

    private static String userPrompt(String oldSummary, List<ChatMessage> older) {
        StringBuilder sb = new StringBuilder("已有摘要:\n")
                .append(oldSummary == null || oldSummary.isBlank() ? "(无)" : oldSummary)
                .append("\n\n新增对话:\n");
        for (ChatMessage m : older) {
            sb.append(m.role()).append(": ").append(m.content()).append('\n');
        }
        return sb.toString();
    }
}
