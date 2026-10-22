package com.mall.agent.data;

import com.mall.agent.data.OrderView.OrderLine;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/** 所有查询都带 user_id:订单归属校验放在 SQL 里,不依赖上层记得检查。 */
@Repository
public class OrderRepo {

    private final JdbcClient jdbc;

    public OrderRepo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OrderView> findByNoAndUser(String orderNo, String userId) {
        return jdbc.sql("SELECT * FROM orders WHERE order_no = ? AND user_id = ?")
                .params(orderNo, userId).query(this::map).optional();
    }

    public List<OrderView> listByUser(String userId, String status, int limit) {
        if (status == null || status.isBlank()) {
            return jdbc.sql("SELECT * FROM orders WHERE user_id = ? ORDER BY created_at DESC LIMIT ?")
                    .params(userId, limit).query(this::map).list();
        }
        return jdbc.sql("SELECT * FROM orders WHERE user_id = ? AND status = ? ORDER BY created_at DESC LIMIT ?")
                .params(userId, status, limit).query(this::map).list();
    }

    private OrderView map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        String orderNo = rs.getString("order_no");
        List<OrderLine> items = jdbc.sql("SELECT product_name, price, quantity FROM order_item WHERE order_no = ?")
                .param(orderNo)
                .query((r, n) -> new OrderLine(r.getString(1), r.getBigDecimal(2), r.getInt(3))).list();
        return new OrderView(orderNo, rs.getString("status"), rs.getBigDecimal("total_amount"),
                rs.getString("receiver"), rs.getString("address"),
                time(rs.getTimestamp("created_at")), time(rs.getTimestamp("shipped_at")), items);
    }

    private static java.time.LocalDateTime time(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }
}
