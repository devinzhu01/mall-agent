package com.mall.agent.memory;

import com.mall.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShortTermMemoryTest {

    StringRedisTemplate template = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ListOperations<String, String> ops = mock(ListOperations.class);
    List<String> store = new ArrayList<>();
    AgentProperties props = new AgentProperties();
    ShortTermMemory memory;

    @BeforeEach
    void setUp() {
        when(template.opsForList()).thenReturn(ops);
        when(ops.rightPushAll(anyString(), any(Collection.class))).thenAnswer(inv -> {
            store.addAll(inv.getArgument(1));
            return (long) store.size();
        });
        when(ops.range(anyString(), anyLong(), anyLong())).thenAnswer(inv -> new ArrayList<>(store));
        when(template.delete(anyString())).thenAnswer(inv -> { store.clear(); return true; });
        memory = new ShortTermMemory(template, props);
    }

    @Test
    void appendAndLoadKeepsOrder() {
        memory.append("s1", List.of(ChatMessage.user("你好"), ChatMessage.assistant("您好,有什么可以帮您?")));
        memory.append("s1", List.of(ChatMessage.user("查订单")));
        assertThat(memory.load("s1")).containsExactly(
                ChatMessage.user("你好"), ChatMessage.assistant("您好,有什么可以帮您?"), ChatMessage.user("查订单"));
    }

    @Test
    void ttlIsSetOnAppend() {
        memory.append("s1", List.of(ChatMessage.user("hi")));
        verify(template).expire("chat:s1", Duration.ofMinutes(120));
    }

    @Test
    void replaceOverwritesHistory() {
        memory.append("s1", List.of(ChatMessage.user("old1"), ChatMessage.user("old2")));
        memory.replace("s1", List.of(ChatMessage.user("kept")));
        assertThat(memory.load("s1")).containsExactly(ChatMessage.user("kept"));
    }

    @Test
    void emptyAppendDoesNothing() {
        memory.append("s1", List.of());
        assertThat(memory.load("s1")).isEmpty();
    }
}
