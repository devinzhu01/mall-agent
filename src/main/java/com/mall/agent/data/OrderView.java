package com.mall.agent.data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderView(String orderNo, String status, BigDecimal totalAmount, String receiver, String address,
                        LocalDateTime createdAt, LocalDateTime shippedAt, List<OrderLine> items) {

    public record OrderLine(String productName, BigDecimal price, int quantity) {
    }
}
