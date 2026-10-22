package com.mall.agent.memory;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.data.Preference;
import com.mall.agent.data.PreferenceRepo;
import com.mall.agent.data.SummaryRepo;
import com.mall.agent.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * 记忆的统一入口。三层各管一件事:
 *  短期 — Redis 里同一会话的近期原文;摘要 — 早期历史压缩后的事实(MySQL,Redis 过期也能恢复);
 *  长期 — 用户偏好(MySQL,跨会话)。
 * 记忆是增强而不是必需:Redis/DB 出问题时降级为"没有记忆",绝不让用户的请求失败。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final ShortTermMemory shortTerm;
    private final HistoryCompactor compactor;
    private final SummaryRepo summaries;
    private final PreferenceRepo preferences;
    private final PreferenceExtractor extractor;
    private final AgentProperties props;

    public MemoryService(ShortTermMemory shortTerm, HistoryCompactor compactor, SummaryRepo summaries,
                         PreferenceRepo preferences, PreferenceExtractor extractor, AgentProperties props) {
        this.shortTerm = shortTerm;
        this.compactor = compactor;
        this.summaries = summaries;
        this.preferences = preferences;
        this.extractor = extractor;
        this.props = props;
    }

    public MemoryContext load(String userId, String sessionId) {
        String summary = safe(() -> summaries.get(sessionId).orElse(""), "");
        List<ChatMessage> recent = safe(() -> window(shortTerm.load(sessionId)), List.of());
        List<Preference> prefs = safe(() -> preferences.list(userId), List.of());
        return new MemoryContext(summary, recent, prefs);
    }

    /** 回答结束后调用:追加本轮消息 → 必要时压缩 → 异步抽取偏好。 */
    public void save(String userId, String sessionId, String userMessage, String assistantMessage, Tracer tracer) {
        try {
            shortTerm.append(sessionId, List.of(ChatMessage.user(userMessage), ChatMessage.assistant(assistantMessage)));
            String oldSummary = summaries.get(sessionId).orElse("");
            List<ChatMessage> all = shortTerm.load(sessionId);
            HistoryCompactor.Compacted c = compactor.compact(oldSummary, all, tracer);
            if (c.recent().size() < all.size()) {
                int folded = all.size() - c.recent().size();
                if (!c.summary().equals(oldSummary)) {
                    summaries.save(sessionId, userId, c.summary(), summaries.coveredCount(sessionId) + folded);
                }
                shortTerm.replace(sessionId, c.recent());
            }
        } catch (Exception e) {
            log.warn("saving memory failed for session {}: {}", sessionId, e.toString());
        }
        extractor.extractAndSave(userId, userMessage, assistantMessage);
    }

    /** 按条数和字符数双重限制注入上下文的历史,优先保留最新的消息。 */
    private List<ChatMessage> window(List<ChatMessage> all) {
        AgentProperties.Memory cfg = props.getMemory();
        List<ChatMessage> kept = new ArrayList<>();
        int chars = 0;
        for (int i = all.size() - 1; i >= 0 && kept.size() < cfg.getWindowMessages(); i--) {
            ChatMessage m = all.get(i);
            if (!kept.isEmpty() && chars + m.content().length() > cfg.getMaxChars()) {
                break;
            }
            chars += m.content().length();
            kept.add(m);
        }
        Collections.reverse(kept);
        return kept;
    }

    private static <T> T safe(Supplier<T> s, T fallback) {
        try {
            return s.get();
        } catch (Exception e) {
            log.warn("memory read failed, degrading to empty: {}", e.toString());
            return fallback;
        }
    }
}
