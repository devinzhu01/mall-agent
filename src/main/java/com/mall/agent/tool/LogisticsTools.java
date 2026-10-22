package com.mall.agent.tool;

import com.mall.agent.data.OrderRepo;
import com.mall.agent.data.OrderView;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.LinkedHashMap;
import java.util.Map;

/** 物流查询:先校验订单归属,通过后才调外部接口;外部接口不可用时降级,不编造轨迹。 */
public class LogisticsTools {

    private final OrderRepo orders;
    private final LogisticsClient client;

    public LogisticsTools(OrderRepo orders, LogisticsClient client) {
        this.orders = orders;
        this.client = client;
    }

    @Tool(description = "查询订单的物流轨迹。用户问'到哪了'、'什么时候到'、'快递信息'时使用;只查订单状态用 getOrder。")
    public String getLogistics(@ToolParam(description = "16位数字订单号") String orderNo, ToolContext ctx) {
        String userId = ToolKeys.userId(ctx);
        OrderTools.requireOrderNo(orderNo);
        OrderView order = orders.findByNoAndUser(orderNo, userId).orElseThrow(OrderTools::notFound);

        if (order.shippedAt() == null) {
            // 没发货就没有物流:不调用外部接口,更不会有编造轨迹的机会
            return ToolResult.ok(Map.of("orderStatus", order.status(), "available", false,
                    "message", "订单尚未发货,当前状态为 " + order.status() + ",发货后才有物流信息")).toJson();
        }
        LogisticsInfo info = client.query(orderNo);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderStatus", order.status());
        data.put("available", info.available());
        if (info.available()) {
            data.put("carrier", info.carrier());
            data.put("status", info.status());
            data.put("events", info.events());
        } else {
            data.put("message", "物流信息暂时无法获取,订单状态为 " + order.status() + ",请稍后再试");
        }
        return ToolResult.ok(data).toJson();
    }
}
