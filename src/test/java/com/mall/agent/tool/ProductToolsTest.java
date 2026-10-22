package com.mall.agent.tool;

import com.mall.agent.data.Product;
import com.mall.agent.data.ProductRepo;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductToolsTest {

    private final ProductRepo repo = mock(ProductRepo.class);
    private final ProductTools tools = new ProductTools(repo);
    private final ToolContext ctx = new ToolContext(Map.of(ToolKeys.USER_ID, "u1001"));

    @Test
    void searchProductsMapsFilters() {
        when(repo.search(eq("手机"), eq("手机"), eq(new BigDecimal("2000.0")), eq(new BigDecimal("4000.0")),
                eq("华为"), anyInt())).thenReturn(List.of(
                new Product(2, "华为 nova 12", "手机", "华为", new BigDecimal("2699.00"), 10, "前置人像", null)));
        String json = tools.searchProducts("手机", "手机", 2000.0, 4000.0, "华为", ctx);
        assertThat(json).contains("\"ok\":true").contains("华为 nova 12").contains("2699");
        verify(repo).search(eq("手机"), eq("手机"), eq(new BigDecimal("2000.0")), eq(new BigDecimal("4000.0")),
                eq("华为"), anyInt());
    }

    @Test
    void searchProductsRejectsInvertedPriceRange() {
        assertThatThrownBy(() -> tools.searchProducts(null, null, 5000.0, 1000.0, null, ctx))
                .isInstanceOfSatisfying(ToolException.class, e -> assertThat(e.code()).isEqualTo("INVALID_ARG"));
    }

    @Test
    void getProductDetailNotFound() {
        when(repo.findById(99L)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> tools.getProductDetail(99L, ctx))
                .isInstanceOfSatisfying(ToolException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
    }
}
