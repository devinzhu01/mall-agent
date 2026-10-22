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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderToolsTest {

    private final OrderRepo repo = mock(OrderRepo.class);
    private final OrderTools tools = new OrderTools(repo);
    private final ToolContext u1002 = new ToolContext(Map.of(ToolKeys.USER_ID, "u1002"));
    private final ToolContext u1001 = new ToolContext(Map.of(ToolKeys.USER_ID, "u1001"));

    private static OrderView order(String no) {
        return new OrderView(no, "SHIPPED", new BigDecimal("2699.00"), "张三", "上海", LocalDateTime.now(), null,
                List.of(new OrderView.OrderLine("华为 nova 12", new BigDecimal("2699.00"), 1)));
    }

    @Test
    void getOrderRejectsMalformedNo() {
        assertThatThrownBy(() -> tools.getOrder("ABC123", u1001))
                .isInstanceOfSatisfying(ToolException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVALID_ARG");
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.hint()).contains("16位数字");
                });
    }

    @Test
    void getOrderOfAnotherUserLooksLikeNotFound() {
        when(repo.findByNoAndUser("2026100100000001", "u1002")).thenReturn(Optional.empty());
        when(repo.findByNoAndUser("2026999999999999", "u1002")).thenReturn(Optional.empty());

        ToolException other = catchTool(() -> tools.getOrder("2026100100000001", u1002));
        ToolException missing = catchTool(() -> tools.getOrder("2026999999999999", u1002));
        assertThat(other.toResult().toJson()).isEqualTo(missing.toResult().toJson());
        assertThat(other.code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void getOrderReturnsOrderForOwner() {
        when(repo.findByNoAndUser("2026100100000001", "u1001")).thenReturn(Optional.of(order("2026100100000001")));
        String json = tools.getOrder("2026100100000001", u1001);
        assertThat(json).contains("\"ok\":true").contains("SHIPPED").contains("华为 nova 12");
    }

    @Test
    void listMyOrdersRejectsUnknownStatus() {
        assertThatThrownBy(() -> tools.listMyOrders("FLYING", u1001))
                .isInstanceOfSatisfying(ToolException.class, e -> assertThat(e.code()).isEqualTo("INVALID_ARG"));
    }

    @Test
    void listMyOrdersPassesCallerUserIdAndStatus() {
        when(repo.listByUser(eq("u1001"), eq("DONE"), anyInt())).thenReturn(List.of(order("2026100100000002")));
        assertThat(tools.listMyOrders("DONE", u1001)).contains("2026100100000002");
    }

    @Test
    void missingUserInContextIsUnauthorized() {
        assertThatThrownBy(() -> tools.listMyOrders(null, new ToolContext(Map.of())))
                .isInstanceOfSatisfying(ToolException.class, e -> assertThat(e.code()).isEqualTo("UNAUTHORIZED"));
    }

    @Test
    void toolMethodsDoNotExposeUserIdParameter() {
        for (Object bean : List.of(tools, new ProductTools(mock(com.mall.agent.data.ProductRepo.class)),
                new LogisticsTools(repo, mock(LogisticsClient.class)))) {
            for (var cb : org.springframework.ai.support.ToolCallbacks.from(bean)) {
                assertThat(cb.getToolDefinition().inputSchema()).doesNotContainIgnoringCase("userId");
            }
        }
    }

    private static ToolException catchTool(Runnable r) {
        try {
            r.run();
        } catch (ToolException e) {
            return e;
        }
        throw new AssertionError("expected ToolException");
    }
}
