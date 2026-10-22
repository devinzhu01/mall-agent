package com.mall.agent.tool;

import com.mall.agent.data.OrderRepo;
import com.mall.agent.data.OrderView;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 没发货的订单不可能有物流:不能调用物流接口,更不能返回编造的轨迹。 */
class LogisticsUnshippedTest {

    private final OrderRepo orders = mock(OrderRepo.class);
    private final LogisticsClient client = mock(LogisticsClient.class);
    private final LogisticsTools tools = new LogisticsTools(orders, client);
    private final ToolContext ctx = new ToolContext(Map.of(ToolKeys.USER_ID, "u1001"));

    @Test
    void unshippedOrderNeverCallsLogisticsAndSaysNotShipped() {
        OrderView paid = new OrderView("2026100100000003", "PAID", new BigDecimal("1299"), "张三", "上海",
                LocalDateTime.now(), null, List.of());
        when(orders.findByNoAndUser("2026100100000003", "u1001")).thenReturn(Optional.of(paid));

        String json = tools.getLogistics("2026100100000003", ctx);

        assertThat(json).contains("\"available\":false").contains("尚未发货").contains("PAID").doesNotContain("events");
        verify(client, never()).query(anyString());
    }
}
