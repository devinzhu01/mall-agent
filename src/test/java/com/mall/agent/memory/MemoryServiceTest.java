package com.mall.agent.memory;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.data.Preference;
import com.mall.agent.data.PreferenceRepo;
import com.mall.agent.data.SummaryRepo;
import com.mall.agent.trace.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryServiceTest {

    ShortTermMemory stm = mock(ShortTermMemory.class);
    HistoryCompactor compactor = mock(HistoryCompactor.class);
    SummaryRepo summaries = mock(SummaryRepo.class);
    PreferenceRepo prefs = mock(PreferenceRepo.class);
    PreferenceExtractor extractor = mock(PreferenceExtractor.class);
    AgentProperties props = new AgentProperties();
    MemoryService service;

    @BeforeEach
    void setUp() {
        service = new MemoryService(stm, compactor, summaries, prefs, extractor, props);
    }

    private static List<ChatMessage> messages(int n, int charsEach) {
        List<ChatMessage> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(ChatMessage.user(("m" + i + "-").repeat(1) + "x".repeat(charsEach)));
        }
        return list;
    }

    @Test
    void loadCombinesSummaryRecentAndPreferences() {
        when(summaries.get("s1")).thenReturn(Optional.of("摘要"));
        when(stm.load("s1")).thenReturn(List.of(ChatMessage.user("hi")));
        when(prefs.list("u1")).thenReturn(List.of(new Preference("brand", "华为", 0.9)));

        MemoryContext ctx = service.load("u1", "s1");

        assertThat(ctx.summary()).isEqualTo("摘要");
        assertThat(ctx.recent()).containsExactly(ChatMessage.user("hi"));
        assertThat(ctx.preferences()).hasSize(1);
    }

    @Test
    void loadWindowTrimsByCount() {
        when(summaries.get("s1")).thenReturn(Optional.empty());
        when(stm.load("s1")).thenReturn(messages(30, 5));
        assertThat(service.load("u1", "s1").recent()).hasSize(20);
    }

    @Test
    void loadWindowTrimsByCharsKeepingNewest() {
        when(summaries.get("s1")).thenReturn(Optional.empty());
        List<ChatMessage> msgs = messages(10, 1000);   // 10 条 × ~1000 字符 > 6000
        when(stm.load("s1")).thenReturn(msgs);
        List<ChatMessage> recent = service.load("u1", "s1").recent();
        assertThat(recent.size()).isLessThan(10);
        assertThat(recent.get(recent.size() - 1)).isEqualTo(msgs.get(9));
    }

    @Test
    void loadDegradesWhenRedisDown() {
        when(summaries.get("s1")).thenReturn(Optional.of("摘要"));
        when(stm.load("s1")).thenThrow(new RuntimeException("redis down"));
        MemoryContext ctx = service.load("u1", "s1");
        assertThat(ctx.recent()).isEmpty();
        assertThat(ctx.summary()).isEqualTo("摘要");
    }

    @Test
    void saveAppendsThenTriggersPreferenceExtraction() {
        List<ChatMessage> loaded = List.of(ChatMessage.user("q"), ChatMessage.assistant("a"));
        when(summaries.get("s1")).thenReturn(Optional.of("旧"));
        when(stm.load("s1")).thenReturn(loaded);
        when(compactor.compact(eq("旧"), eq(loaded), any())).thenReturn(new HistoryCompactor.Compacted("旧", loaded));

        service.save("u1", "s1", "q", "a", Tracer.NOOP);

        verify(stm).append("s1", List.of(ChatMessage.user("q"), ChatMessage.assistant("a")));
        verify(extractor).extractAndSave("u1", "q", "a");
        verify(summaries, never()).save(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void saveStoresNewSummaryAndReplacesRedisHistoryWhenCompacted() {
        List<ChatMessage> loaded = messages(20, 3);
        List<ChatMessage> recent = loaded.subList(14, 20);
        when(summaries.get("s1")).thenReturn(Optional.of("旧"));
        when(summaries.coveredCount("s1")).thenReturn(4);
        when(stm.load("s1")).thenReturn(loaded);
        when(compactor.compact(eq("旧"), eq(loaded), any())).thenReturn(new HistoryCompactor.Compacted("新摘要", recent));

        service.save("u1", "s1", "q", "a", Tracer.NOOP);

        verify(summaries).save("s1", "u1", "新摘要", 4 + 14);
        verify(stm).replace("s1", recent);
    }

    @Test
    void saveSurvivesRedisFailure() {
        org.mockito.Mockito.doThrow(new RuntimeException("redis down")).when(stm).append(anyString(), anyList());
        service.save("u1", "s1", "q", "a", Tracer.NOOP);   // 不应抛出:回答已经发给用户
        verify(extractor).extractAndSave("u1", "q", "a");
    }

    @Test
    void preferencesTextFormat() {
        var ctx = new MemoryContext("", List.of(), List.of(
                new Preference("brand", "华为", 0.9), new Preference("budget", "2000-3000", 0.8)));
        assertThat(ctx.preferencesText()).isEqualTo("用户偏好:brand=华为;budget=2000-3000");
        assertThat(new MemoryContext("", List.of(), List.of()).preferencesText()).isEmpty();
    }
}
