package com.mall.agent.memory;

import com.mall.agent.data.Preference;

import java.util.List;
import java.util.stream.Collectors;

/** 一次请求开始时加载的全部记忆:摘要(会话内压缩)+ 最近消息(短期)+ 偏好(长期,跨会话)。 */
public record MemoryContext(String summary, List<ChatMessage> recent, List<Preference> preferences) {

    /** 渲染成一行文本注入推荐专家的提示词;没有偏好返回空串。 */
    public String preferencesText() {
        if (preferences.isEmpty()) {
            return "";
        }
        return "用户偏好:" + preferences.stream().map(p -> p.key() + "=" + p.value()).collect(Collectors.joining(";"));
    }
}
