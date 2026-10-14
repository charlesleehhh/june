package fun.charleslee.radar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从数据库聚合生成前端 data/prices.json，结构与前端 app.js 的预期完全一致。先写临时文件再原子替换。 */
public class JsonGenerator {

    public static void generate(Db db, Config cfg) throws Exception {
        ObjectMapper om = new ObjectMapper();
        om.enable(SerializationFeature.INDENT_OUTPUT);

        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("updatedAt", LocalDate.now().toString());
        meta.put("launchDate", cfg.launchDate);
        meta.put("currency", "CNY");
        meta.put("title", "果价雷达");
        meta.put("subtitle", "iPhone 18 Pro 全网比价");
        meta.put("notice", "价格均为公开渠道信息整理，仅供比价参考，不构成购买建议。请以各平台实时页面为准。");
        root.put("meta", meta);

        List<Map<String, Object>> variants = new ArrayList<>();
        for (Map<String, Object> v : db.query(
                "SELECT code, series, storage, gb, official_price FROM variant WHERE status=1 ORDER BY series, gb")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.get("code"));
            m.put("series", v.get("series"));
            m.put("storage", v.get("storage"));
            m.put("gb", ((Number) v.get("gb")).intValue());
            m.put("official", ((Number) v.get("official_price")).intValue());
            variants.add(m);
        }
        root.put("variants", variants);

        List<Map<String, Object>> channels = new ArrayList<>();
        for (Map<String, Object> c : db.query(
                "SELECT code, name, kind, sort, service, buy_url FROM channel WHERE status=1 ORDER BY sort")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.get("code"));
            m.put("name", c.get("name"));
            m.put("kind", c.get("kind"));
            m.put("sort", ((Number) c.get("sort")).intValue());
            m.put("service", c.get("service"));
            m.put("buyUrl", c.get("buy_url"));
            channels.add(m);
        }
        root.put("channels", channels);

        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> r : db.query(
                "SELECT v.code AS variant, c.code AS channel, r.trade_date, r.price, r.in_stock, r.promo, r.source " +
                "FROM price_record r JOIN variant v ON v.id=r.variant_id JOIN channel c ON c.id=r.channel_id " +
                "ORDER BY r.trade_date, v.code, c.code")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("variant", r.get("variant"));
            m.put("channel", r.get("channel"));
            m.put("date", ((Date) r.get("trade_date")).toLocalDate().toString());
            m.put("price", ((Number) r.get("price")).intValue());
            m.put("inStock", ((Number) r.get("in_stock")).intValue() == 1);
            m.put("promo", r.get("promo"));
            m.put("source", r.get("source"));
            records.add(m);
        }
        root.put("records", records);

        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> e : db.query("SELECT ev_date, kind, title, body FROM market_event ORDER BY ev_date")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", ((Date) e.get("ev_date")).toLocalDate().toString());
            m.put("kind", e.get("kind"));
            m.put("title", e.get("title"));
            m.put("body", e.get("body"));
            events.add(m);
        }
        root.put("events", events);

        // 真实成交记录（苹果团订单，供前端「最近成交」展示，增强可信度）
        List<Map<String, Object>> orders = new ArrayList<>();
        for (Map<String, Object> o : db.query(
                "SELECT o.order_no, o.trade_at, v.code AS variant, c.code AS channel, o.color, o.list_price, o.paid " +
                "FROM tuan_order o JOIN variant v ON v.id=o.variant_id JOIN channel c ON c.id=o.channel_id " +
                "ORDER BY o.trade_at DESC LIMIT 40")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderNo", mask(String.valueOf(o.get("order_no"))));
            m.put("at", String.valueOf(o.get("trade_at")).replace(".0", ""));
            m.put("variant", o.get("variant"));
            m.put("channel", o.get("channel"));
            m.put("color", o.get("color"));
            m.put("listPrice", ((Number) o.get("list_price")).intValue());
            m.put("paid", Math.round(((Number) o.get("paid")).doubleValue()));
            orders.add(m);
        }
        root.put("orders", orders);

        Path out = Paths.get(cfg.outputJsonPath);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Path tmp = Paths.get(out + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
            om.writeValue(fos, root);
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** 订单号脱敏：尾号 4 位打码后展示。 */
    private static String mask(String orderNo) {
        if (orderNo == null || orderNo.length() < 8) return "****";
        return orderNo.substring(0, 8) + "****" + orderNo.substring(orderNo.length() - 4);
    }
}
