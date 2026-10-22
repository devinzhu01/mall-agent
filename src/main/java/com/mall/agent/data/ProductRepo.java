package com.mall.agent.data;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class ProductRepo {

    private final JdbcClient jdbc;

    public ProductRepo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 只查在售商品;条件为空则不过滤。关键词匹配名称/卖点/品牌。 */
    public List<Product> search(String keyword, String category, BigDecimal min, BigDecimal max,
                                String brand, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM product WHERE on_sale = 1");
        List<Object> args = new ArrayList<>();
        if (notBlank(keyword)) {
            sql.append(" AND (name LIKE ? OR description LIKE ? OR brand LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like); args.add(like); args.add(like);
        }
        if (notBlank(category)) { sql.append(" AND category = ?"); args.add(category); }
        if (notBlank(brand))    { sql.append(" AND brand = ?");    args.add(brand); }
        if (min != null)        { sql.append(" AND price >= ?");   args.add(min); }
        if (max != null)        { sql.append(" AND price <= ?");   args.add(max); }
        sql.append(" ORDER BY price LIMIT ?");
        args.add(limit);
        return jdbc.sql(sql.toString()).params(args).query(ProductRepo::map).list();
    }

    public Optional<Product> findById(long id) {
        return jdbc.sql("SELECT * FROM product WHERE id = ?").param(id).query(ProductRepo::map).optional();
    }

    private static Product map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Product(rs.getLong("id"), rs.getString("name"), rs.getString("category"),
                rs.getString("brand"), rs.getBigDecimal("price"), rs.getInt("stock"),
                rs.getString("description"), rs.getString("attrs"));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
