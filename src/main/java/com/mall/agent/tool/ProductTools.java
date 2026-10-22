package com.mall.agent.tool;

import com.mall.agent.data.Product;
import com.mall.agent.data.ProductRepo;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 推荐专家的工具。返回精简字段,节省 token。 */
public class ProductTools {

    private static final int LIMIT = 5;

    private final ProductRepo repo;

    public ProductTools(ProductRepo repo) {
        this.repo = repo;
    }

    @Tool(description = "按条件搜索在售商品。用户想买东西、要推荐、问有哪些商品时使用;"
            + "查订单、问售后规则时不要使用。所有条件都可不填,最多返回5件,按价格升序。")
    public String searchProducts(
            @ToolParam(description = "关键词,如 羽绒服、降噪耳机", required = false) String keyword,
            @ToolParam(description = "类目,可选:手机/服饰/鞋靴/家电/电脑/数码/美妆/食品/日用", required = false) String category,
            @ToolParam(description = "最低价(元)", required = false) Double minPrice,
            @ToolParam(description = "最高价(元)", required = false) Double maxPrice,
            @ToolParam(description = "品牌,如 华为、小米", required = false) String brand,
            ToolContext ctx) {
        if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
            throw new ToolException("INVALID_ARG", "最低价大于最高价", true, "请确认价格区间后重新调用");
        }
        List<Product> found = repo.search(keyword, category, decimal(minPrice), decimal(maxPrice), brand, LIMIT);
        return ToolResult.ok(found.stream().map(ProductTools::brief).toList()).toJson();
    }

    @Tool(description = "按商品ID查询商品详情(含规格属性)。已经通过搜索拿到商品ID、用户想了解某件商品细节时使用。")
    public String getProductDetail(@ToolParam(description = "商品ID") long productId, ToolContext ctx) {
        Product p = repo.findById(productId)
                .orElseThrow(() -> new ToolException("NOT_FOUND", "未找到该商品", false, null));
        Map<String, Object> m = brief(p);
        m.put("attrs", p.attrs());
        return ToolResult.ok(m).toJson();
    }

    private static Map<String, Object> brief(Product p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("name", p.name());
        m.put("brand", p.brand());
        m.put("price", p.price());
        m.put("inStock", p.stock() > 0);
        m.put("description", p.description());
        return m;
    }

    private static BigDecimal decimal(Double d) {
        return d == null ? null : BigDecimal.valueOf(d);
    }
}
