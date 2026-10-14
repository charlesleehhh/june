package fun.charleslee.radar;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 极简 JDBC 封装，无连接池（单次运行、几分钟内退出，够用）。 */
public class Db implements AutoCloseable {
    private final Connection conn;

    public Db(Config cfg) throws SQLException {
        this.conn = DriverManager.getConnection(cfg.dbUrl, cfg.dbUser, cfg.dbPassword);
    }

    public List<Map<String, Object>> query(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    public int update(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        }
    }

    /** 取某配置+渠道最近一次价格（按日期倒序取第一条），用于填充 prev_price。 */
    public Integer latestPrice(int variantId, int channelId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT price FROM price_record WHERE variant_id=? AND channel_id=? " +
                "ORDER BY trade_date DESC, id DESC LIMIT 1")) {
            ps.setInt(1, variantId);
            ps.setInt(2, channelId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    /** 同日重复执行时更新当日价格，不产生重复快照。 */
    public void upsertPrice(int variantId, int channelId, String date, int price,
                            boolean inStock, String promo, String source) throws SQLException {
        Integer prev = latestPrice(variantId, channelId);
        update("INSERT INTO price_record (variant_id, channel_id, trade_date, price, in_stock, promo, source) " +
               "VALUES (?,?,?,?,?,?,?) " +
               "ON DUPLICATE KEY UPDATE price=VALUES(price), in_stock=VALUES(in_stock), " +
               "promo=VALUES(promo), source=VALUES(source), collected_at=NOW()",
               variantId, channelId, date, price, inStock ? 1 : 0, promo, source);
        if (prev != null && prev != price) {
            update("UPDATE price_record SET prev_price=? WHERE variant_id=? AND channel_id=? AND trade_date=?",
                   prev, variantId, channelId, date);
        }
    }

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
    }

    @Override
    public void close() throws SQLException {
        try (Statement st = conn.createStatement()) { /* noop */ }
        conn.close();
    }
}
