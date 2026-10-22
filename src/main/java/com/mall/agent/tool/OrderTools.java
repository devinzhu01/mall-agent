package com.mall.agent.tool;

import com.mall.agent.data.OrderRepo;
import com.mall.agent.data.OrderView;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 订单专家的工具。userId 从 ToolContext 取;订单不存在和不属于当前用户返回完全相同的错误,
 * 避免通过试探订单号泄露别人订单是否存在。
 */
public class OrderTools {

    static final Pattern ORDER_NO = Pattern.compile("^\\d{16}$");
    static final Set<String> STATUSES = Set.of("UNPAID", "PAID", "SHIPPED", "DONE", "REFUNDING", "CLOSED");

    private final OrderRepo repo;

    public OrderTools(OrderRepo repo) {
        this.repo = repo;
    }

    @Tool(description = "按订单号查询当前用户自己的订单详情(状态、金额、商品、收货信息)。"
            + "用户给出了订单号并想了解订单情况时使用;只想知道物流轨迹用 getLogistics;没有订单号先用 listMyOrders。")
    public String getOrder(@ToolParam(description = "16位数字订单号") String orderNo, ToolContext ctx) {
        String userId = ToolKeys.userId(ctx);
        requireOrderNo(orderNo);
        OrderView o = repo.findByNoAndUser(orderNo, userId).orElseThrow(OrderTools::notFound);
        return ToolResult.ok(view(o)).toJson();
    }

    @Tool(description = "列出当前用户最近的订单(最多10条),可按状态筛选。用户没给订单号、问'我的订单'、'最近买了什么'时使用。")
    public String listMyOrders(
            @ToolParam(description = "订单状态,可选 UNPAID/PAID/SHIPPED/DONE/REFUNDING/CLOSED;不填则不筛选", required = false)
            String status, ToolContext ctx) {
        String userId = ToolKeys.userId(ctx);
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) {
            throw new ToolException("INVALID_ARG", "不支持的订单状态: " + status, true,
                    "状态只能是 " + String.join("/", STATUSES.stream().sorted().toList()));
        }
        List<OrderView> list = repo.listByUser(userId, status, 10);
        return ToolResult.ok(list.stream().map(OrderTools::view).toList()).toJson();
    }

    static void requireOrderNo(String orderNo) {
        if (orderNo == null || !ORDER_NO.matcher(orderNo).matches()) {
            throw new ToolException("INVALID_ARG", "订单号格式不正确", true, "订单号为16位数字,请向用户确认后重试");
        }
    }

    static ToolException notFound() {
        return new ToolException("NOT_FOUND", "未找到该订单", false, null);
    }

    private static Map<String, Object> view(OrderView o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderNo", o.orderNo());
        m.put("status", o.status());
        m.put("totalAmount", o.totalAmount());
        m.put("receiver", o.receiver());
        m.put("address", o.address());
        m.put("createdAt", String.valueOf(o.createdAt()));
        m.put("shippedAt", o.shippedAt() == null ? null : o.shippedAt().toString());
        m.put("items", o.items());
        return m;
    }
}
