package com.mall.agent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.agent.config.AgentProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;

/** 短期记忆:同一会话的近期消息,存 Redis List(chat:{sessionId}),每次写入刷新过期时间。 */
@Component
public class ShortTermMemory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StringRedisTemplate redis;
    private final AgentProperties props;

    public ShortTermMemory(StringRedisTemplate redis, AgentProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public void append(String sessionId, List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        String key = key(sessionId);
        redis.opsForList().rightPushAll(key, messages.stream().map(ShortTermMemory::toJson).toList());
        redis.expire(key, Duration.ofMinutes(props.getMemory().getTtlMinutes()));
    }

    public List<ChatMessage> load(String sessionId) {
        List<String> raw = redis.opsForList().range(key(sessionId), 0, -1);
        return raw == null ? List.of() : raw.stream().map(ShortTermMemory::fromJson).toList();
    }

    /** 压缩之后用"最近几条"覆盖整个历史。 */
    public void replace(String sessionId, List<ChatMessage> messages) {
        redis.delete(key(sessionId));
        append(sessionId, messages);
    }

    private static String key(String sessionId) {
        return "chat:" + sessionId;
    }

    private static String toJson(ChatMessage m) {
        try {
            return MAPPER.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ChatMessage fromJson(String s) {
        try {
            return MAPPER.readValue(s, ChatMessage.class);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
