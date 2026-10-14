package fun.charleslee.radar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fun.charleslee.radar.fetch.Fetcher;
import fun.charleslee.radar.fetch.FetchResult;
import fun.charleslee.radar.fetch.AppleFetcher;
import fun.charleslee.radar.fetch.JdFetcher;

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

/**
 * 果价雷达爬虫主程序：单次运行后退出（由 crontab 定时触发，无常驻进程）。
 * 流程：读配置 -> 逐渠道抓取 -> 入库（price_record） -> 从库聚合生成 prices.json（原子写） -> 写 fetch_log。
 */
public class Main {

    public static void main(String[] args) {
        String cfgPath = args.length > 0 ? args[0] : "config.properties";
        int exit;
        try {
            Config cfg = Config.load(cfgPath);
            exit = new Main().run(cfg);
        } catch (Exception e) {
            System.err.println("[FATAL] " + e.getMessage());
            e.printStackTrace();
            exit = 2;
        }
        System.exit(exit);
    }

    private int run(Config cfg) {
        int written = 0;
        StringBuilder logMsg = new StringBuilder();
        String overall = "OK";

        try (Db db = new Db(cfg)) {
            List<Fetcher> fetchers = new ArrayList<>();
            if (cfg.appleEnabled) fetchers.add(new AppleFetcher());
            if (cfg.jdEnabled) fetchers.add(new JdFetcher());

            String today = LocalDate.now().toString();
            int failures = 0;

            for (Fetcher f : fetchers) {
                try {
                    List<FetchResult> results = f.fetch(cfg);
                    for (FetchResult r : results) {
                        int variantId = requireVariantId(db, r.variantCode);
                        int channelId = requireChannelId(db, r.channelCode);
                        db.upsertPrice(variantId, channelId, today, r.price,
                                r.inStock, r.promo, r.source);
                        written++;
                    }
                    logMsg.append('[').append(f.channelCode()).append("] ok:").append(results.size()).append("; ");
                    System.out.println("[crawler] " + f.channelCode() + " -> " + results.size() + " 条");
                } catch (Exception e) {
                    failures++;
                    logMsg.append('[').append(f.channelCode()).append("] FAIL:").append(e.getMessage()).append("; ");
                    System.err.println("[crawler] " + f.channelCode() + " 失败: " + e.getMessage());
                }
            }

            if (failures > 0 && written == 0) overall = "FAIL";
            else if (failures > 0) overall = "PARTIAL";

            if (written > 0 || "OK".equals(overall)) {
                generateJson(db, cfg);
                System.out.println("[json] 已生成 -> " + cfg.outputJsonPath);
            }
        } catch (Exception e) {
            System.err.println("[FATAL] " + e.getMessage());
            e.printStackTrace();
            logMsg.insert(0, "FATAL: " + e.getMessage() + "; ");
            overall = "FAIL";
        }

        try (Db db2 = new Db(cfg)) {
            db2.update("INSERT INTO fetch_log (status, message, records) VALUES (?,?,?)",
                    overall, truncate(logMsg.toString(), 990), written);
        } catch (Exception ignore) { /* 日志库连不上就不要再吞掉主流程错误 */ }

        System.out.println("[done] status=" + overall + " records=" + written);
        return "FAIL".equals(overall) ? 1 : 0;
    }

    private int requireVariantId(Db db, String code) throws Exception {
        List<Map<String, Object>> rows = db.query("SELECT id FROM variant WHERE code=?", code);
        if (rows.isEmpty()) throw new IllegalStateException("variant 不存在: " + code + "（先执行 database/schema.sql 导入种子数据）");
        return ((Number) rows.get(0).get("id")).intValue();
    }

    private int requireChannelId(Db db, String code) throws Exception {
        List<Map<String, Object>> rows = db.query("SELECT id FROM channel WHERE code=?", code);
        if (rows.isEmpty()) throw new IllegalStateException("channel 不存在: " + code);
        return ((Number) rows.get(0).get("id")).intValue();
    }

    /** 从库聚合生成前端 JSON，结构与 data/prices.json 完全一致；先写临时文件再原子替换。 */
    private void generateJson(Db db, Config cfg) throws Exception {
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
        for (Map<String, Object> v : db.query("SELECT code, series, storage, gb, official_price FROM variant WHERE status=1 ORDER BY series, gb")) {
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
        for (Map<String, Object> c : db.query("SELECT code, name, kind, sort, service, buy_url FROM channel WHERE status=1 ORDER BY sort")) {
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
            Date d = (Date) r.get("trade_date");
            m.put("date", d.toLocalDate().toString());
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
            Date d = (Date) e.get("ev_date");
            m.put("date", d.toLocalDate().toString());
            m.put("kind", e.get("kind"));
            m.put("title", e.get("title"));
            m.put("body", e.get("body"));
            events.add(m);
        }
        root.put("events", events);

        Path out = Paths.get(cfg.outputJsonPath);
        Path parent = out.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = Paths.get(out + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
            om.writeValue(fos, root);
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
