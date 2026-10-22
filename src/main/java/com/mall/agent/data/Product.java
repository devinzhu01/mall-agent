package com.mall.agent.data;

import java.math.BigDecimal;

public record Product(long id, String name, String category, String brand, BigDecimal price,
                      int stock, String description, String attrs) {
}
