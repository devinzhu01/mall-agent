package com.mall.agent.tool;

import com.mall.agent.data.OrderRepo;
import com.mall.agent.data.OrderView;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogisticsToolsTest {

    private final OrderRepo orders = mock(OrderRepo.class);
    private final LogisticsClient client = mock(LogisticsClient.class);
    private final LogisticsTools tools = new LogisticsTools(orders, client);
    private final ToolContext ctx = new ToolContext(Map.of(ToolKeys.USER_ID, "u1001"));

    private static OrderView shipped() {
        return new OrderView("2026100100000001", "SHIPPED", new BigDecimal("2699"), "张三", "上海",
                LocalDateTime.now(), LocalDateTime.now(), List.of());
    }

    @Test
    void logisticsTimeoutDegrades() {
        when(orders.findByNoAndUser("2026100100000001", "u1001")).thenReturn(Optional.of(shipped()));
        when(client.query("2026100100000001")).thenReturn(LogisticsInfo.unavailable());
        String json = tools.getLogistics("2026100100000001", ctx);
        assertThat(json).contains("\"ok\":true").contains("\"available\":false").contains("暂时无法获取")
                .contains("SHIPPED").doesNotContain("events");
    }

    @Test
    void logisticsReturnsEventsWhenAvailable() {
        when(orders.findByNoAndUser("2026100100000001", "u1001")).thenReturn(Optional.of(shipped()));
        when(client.query("2026100100000001")).thenReturn(new LogisticsInfo(true, "顺丰", "运输中",
                List.of(new LogisticsInfo.Event("2026-10-02 09:00", "已揽收"))));
        assertThat(tools.getLogistics("2026100100000001", ctx)).contains("顺丰").contains("已揽收");
    }

    @Test
    void logisticsChecksOwnershipBeforeCalling() {
        when(orders.findByNoAndUser("2026100100000001", "u1001")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> tools.getLogistics("2026100100000001", ctx))
                .isInstanceOfSatisfying(ToolException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
        verify(client, never()).query(anyString());
    }

    @Test
    void clientDegradesOnTimeout() {
        LogisticsClient real = new LogisticsClient(no -> { throw new RuntimeException(new SocketTimeoutException("read")); });
        assertThat(real.query("2026100100000001").available()).isFalse();
    }

    @Test
    void circuitOpensAfterRepeatedFailures() {
        AtomicInteger calls = new AtomicInteger();
        LogisticsClient real = new LogisticsClient(no -> { calls.incrementAndGet(); throw new RuntimeException("500"); });
        for (int i = 0; i < 8; i++) {
            assertThat(real.query("2026100100000001").available()).isFalse();
        }
        // 窗口 5 次调用后熔断打开,之后不再发起真实调用
        assertThat(calls.get()).isEqualTo(5);
    }
}
