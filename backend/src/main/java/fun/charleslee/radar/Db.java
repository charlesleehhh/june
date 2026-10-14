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
        DriverManager.setLoginTimeout(10);
        this.conn = DriverManager.getConnection(withTimeout(cfg.dbUrl), cfg.dbUser, cfg.dbPassword);
    }

    /** 兜底超时，避免数据库不可达时 cron 任务挂死。 */
    private static String withTimeout(String url) {
        StringBuilder sb = new StringBuilder(url);
        if (!url.contains("connectTimeout")) sb.append(url.contains("?") ? "&" : "?").append("connectTimeout=8000");
        if (!url.contains("socketTimeout")) sb.append("&socketTimeout=60000");
        return sb.toString();
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

    /** 写入一条苹果团成交记录（真实订单，用于前端「最近成交」与历史回填）。 */
    public void upsertOrder(String orderNo, String tradeAt, int variantId, int channelId,
                            String color, int listPrice, double paid) throws SQLException {
        update("INSERT INTO tuan_order (order_no, trade_at, variant_id, channel_id, color, list_price, paid) " +
               "VALUES (?,?,?,?,?,?,?) " +
               "ON DUPLICATE KEY UPDATE paid=VALUES(paid), color=VALUES(color)",
               orderNo, tradeAt, variantId, channelId, color, listPrice, paid);
    }

    /** 某配置某渠道在指定日期的最低价成交（用于历史回填）。 */
    public Double minPaidOn(String date, int variantId, int channelId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT MIN(paid) FROM tuan_order WHERE DATE(trade_at)=? AND variant_id=? AND channel_id=?")) {
            ps.setString(1, date);
            ps.setInt(2, variantId);
            ps.setInt(3, channelId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? (Double) rs.getObject(1) : null;
            }
        }
    }

    /** 已入库的成交日期与配置组合，避免重复回填。 */
    public java.util.Set<String> existingOrderKeys() throws SQLException {
        java.util.Set<String> keys = new java.util.HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT DATE(trade_at) d, variant_id v, channel_id c FROM tuan_order");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) keys.add(rs.getString("d") + "|" + rs.getInt("v") + "|" + rs.getInt("c"));
        }
        return keys;
    }

    /** 把 JDBC 异常压成一句人话，方便在 cron 日志里一眼看懂。 */
    public static String reason(Throwable e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (e instanceof java.net.SocketTimeoutException || m.contains("timed out")) {
            return "连接超时或握手被拒（常见原因：本机 IP 不在数据库白名单 / 端口不通）";
        }
        if (e instanceof java.sql.SQLSyntaxErrorException) return "SQL 语法或对象不存在: " + firstLine(m);
        if (e instanceof java.sql.SQLIntegrityConstraintViolationException) return "主键/唯一键冲突: " + firstLine(m);
        if (m.contains("Access denied")) return "账号密码错误或权限不足（Access denied）";
        if (m.contains("Unknown database")) return "数据库不存在，请先创建（CREATE DATABASE ...）";
        if (m.contains("Communications link failure")) return "网络不可达或数据库拒绝了这次连接";
        return firstLine(m);
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    @Override
    public void close() throws SQLException {
        try (Statement st = conn.createStatement()) { /* noop */ }
        conn.close();
    }
}
