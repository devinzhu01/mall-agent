package com.mall.agent.memory;

import com.mall.agent.data.Preference;
import com.mall.agent.data.PreferenceRepo;
import com.mall.agent.llm.Llm;
import com.mall.agent.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 长期记忆写入:每轮对话结束后异步抽取用户偏好(不占用回答的响应时间)。
 * 只接受白名单 key 和置信度 >= 0.5 的结果,避免把玩笑话/一次性需求当成长期偏好。
 */
@Component
public class PreferenceExtractor {

    public record Item(String key, String value, double confidence) {
    }

    public record Extracted(List<Item> items) {
    }

    private static final Logger log = LoggerFactory.getLogger(PreferenceExtractor.class);
    private static final Set<String> KEYS = Set.of("brand", "budget", "size", "style", "category");
    private static final double MIN_CONFIDENCE = 0.5;

    private static final String SYSTEM = """
            你从购物客服对话中抽取用户稳定的购物偏好。只抽取用户明确表达的内容,不要猜测。
            key 只能是:brand(品牌)、budget(预算区间,如 2000-3000)、size(尺码)、style(风格)、category(常买品类)。
            confidence 取 0 到 1:用户明确说"我喜欢/我只买/预算是"为 0.8 以上,随口一提为 0.5 左右。
            没有可抽取的内容时返回空数组。只输出 JSON:{"items":[{"key":"brand","value":"华为","confidence":0.9}]}""";

    private final Llm llm;
    private final PreferenceRepo repo;

    public PreferenceExtractor(Llm llm, PreferenceRepo repo) {
        this.llm = llm;
        this.repo = repo;
    }

    @Async
    public void extractAndSave(String userId, String userMessage, String assistantMessage) {
        try {
            Extracted result = llm.json(Tracer.NOOP, "preference", null, SYSTEM,
                    "用户: " + userMessage + "\n助手: " + assistantMessage, Extracted.class);
            if (result.items() == null) {
                return;
            }
            for (Item i : result.items()) {
                if (KEYS.contains(i.key()) && i.value() != null && !i.value().isBlank()
                        && i.confidence() >= MIN_CONFIDENCE) {
                    repo.upsert(userId, new Preference(i.key(), i.value().trim(), i.confidence()));
                }
            }
        } catch (Exception e) {
            log.warn("preference extraction failed: {}", e.toString());
        }
    }
}
