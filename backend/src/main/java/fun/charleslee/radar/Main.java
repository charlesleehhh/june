package fun.charleslee.radar;

import fun.charleslee.radar.fetch.FetchResult;
import fun.charleslee.radar.fetch.Fetcher;
import fun.charleslee.radar.fetch.JdFetcher;
import fun.charleslee.radar.fetch.TuanFetcher;
import fun.charleslee.radar.fetch.TuanOrder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static fun.charleslee.radar.Tables.CHANNEL;
import static fun.charleslee.radar.Tables.FETCH_LOG;
import static fun.charleslee.radar.Tables.VARIANT;

/**
 * 果价雷达主程序：单次运行后退出（crontab 定时触发，无常驻进程）。
 *
 * 数据流：苹果团报价表 + 成交记录 → 数据库 → 聚合生成前端 data/prices.json
 * 失败隔离：任一来源失败不影响其它来源；0 条写入时绝不覆盖线上 JSON。
 * 常用参数：
 *   config.properties        正常抓取一次（cron 用）
 *   --dry-run config.properties   只抓取并打印结果，不连数据库（用于验证网络与解析）
 *   --serve config.properties     启动管理员后台
 *   --ingest prices.csv config.properties  离线导入
 *   --diagnose config.properties  网络诊断
 */
public class Main {

    public static void main(String[] args) {
        String mode = (args.length > 0 && args[0].startsWith("--")) ? args[0] : "";
        String cfgPath = cfgPath(args);
        try {
            if ("--diagnose".equals(mode)) {
                Diagnose.run(cfgPath);
                return;
            }
            if ("--ingest".equals(mode)) {
                if (args.length < 2) {
                    System.err.println("[FATAL] 用法: --ingest <prices.csv|json> [config.properties]");
                    System.exit(2);
                }
                Ingest.run(args[1], args.length > 2 ? args[2] : "config.properties");
                return;
            }
            if ("--serve".equals(mode)) {
                Config cfg = Config.load(cfgPath);
                System.out.println(cfg.describe());
                AdminServer.start(cfg);
                return;
            }
            if ("--dry-run".equals(mode)) {
                DryRun.run(cfgPath);
                return;
            }
            Config cfg = Config.load(cfgPath);
            System.out.println(cfg.describe());
            System.exit(new Main().run(cfg));
        } catch (IllegalArgumentException e) {
            // 配置类错误：只给人话，不打堆栈（也不会写库）
            System.err.println("[FATAL] " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("[FATAL] " + Db.reason(e));
            System.exit(2);
        }
    }

    /** 取配置文件路径：--xxx 模式下配置路径是最后一个参数（--ingest 另见 main 内单独处理）。 */
    private static String cfgPath(String[] args) {
        if (args.length == 0) return "config.properties";
        if (!args[0].startsWith("--")) return args[0];
        return args.length > 1 ? args[args.length - 1] : "config.properties";
    }

    private int run(Config cfg) {
        int written = 0;
        StringBuilder logMsg = new StringBuilder();
        String overall = "OK";
        Db db = null;

        try {
            db = new Db(cfg);
            List<Fetcher> fetchers = new ArrayList<>();
            if (cfg.tuanEnabled) fetchers.add(new TuanFetcher());
            if (cfg.jdEnabled) fetchers.add(new JdFetcher());

            if (fetchers.isEmpty()) {
                System.out.println("[warn] 没有任何启用的数据来源，仅重新生成 JSON");
            }

            String today = LocalDate.now().toString();
            int failures = 0;

            for (Fetcher f : fetchers) {
                try {
                    List<FetchResult> results = f.fetch(cfg);
                    for (FetchResult r : results) {
                        db.upsertPrice(requireVariantId(db, r.variantCode),
                                requireChannelId(db, r.channelCode),
                                today, r.price, r.inStock, r.promo, r.source);
                        written++;
                    }
                    logMsg.append('[').append(f.channelCode()).append("] ok:").append(results.size()).append("; ");
                    System.out.println("[crawler] " + f.channelCode() + " 当日报价 -> " + results.size() + " 条");
                } catch (Exception e) {
                    failures++;
                    logMsg.append('[').append(f.channelCode()).append("] FAIL:").append(e.getMessage()).append("; ");
                    System.err.println("[crawler] " + f.channelCode() + " 失败: " + e.getMessage());
                }
            }

            // 成交记录：入库 + 按日回填历史价格（走势图的数据基础）
            if (cfg.tuanEnabled) {
                try {
                    int orders = ingestOrders(db, cfg);
                    written += orders;
                    logMsg.append("[tuan-orders] ok:").append(orders).append("; ");
                } catch (Exception e) {
                    failures++;
                    logMsg.append("[tuan-orders] FAIL:").append(e.getMessage()).append("; ");
                    System.err.println("[crawler] 成交记录处理失败: " + e.getMessage());
                }
            }

            if (failures > 0 && written == 0) overall = "FAIL";
            else if (failures > 0) overall = "PARTIAL";

            if (written > 0) {
                JsonGenerator.generate(db, cfg);
                System.out.println("[json] 已生成 -> " + cfg.outputJsonPath);
            } else {
                System.out.println("[json] 本次 0 条写入，保留线上原有数据不动");
            }
        } catch (Exception e) {
            System.err.println("[FATAL] " + Db.reason(e));
            logMsg.insert(0, "FATAL: " + Db.reason(e) + "; ");
            overall = "FAIL";
        } finally {
            if (db != null) {
                try { db.close(); } catch (Exception ignore) { /* 关闭失败无需处理 */ }
            }
        }

        writeLog(cfg, overall, truncate(logMsg.toString(), 990), written);
        System.out.println("[done] status=" + overall + " records=" + written);
        return "FAIL".equals(overall) ? 1 : 0;
    }

    /**
     * 成交记录入库，并按「日期 + 配置 + 渠道」取当日最低成交价回填 price_record。
     * 回填只补历史空档，不覆盖当天抓取到的报价。
     */
    private static int ingestOrders(Db db, Config cfg) throws Exception {
        List<TuanOrder> orders = new TuanFetcher().fetchOrders(cfg);
        int inserted = 0;
        Map<String, Integer> variantIds = new HashMap<>();
        Map<String, Integer> channelIds = new HashMap<>();
        // date|variant|channel -> 当日最低实付价
        Map<String, Double> dailyMin = new HashMap<>();
        Set<String> days = new TreeSet<>();

        for (TuanOrder o : orders) {
            String variant = o.variantCode();
            if (variant == null) continue;
            String channel = o.channelCode();
            int vid = variantIds.computeIfAbsent(variant, k -> {
                try { return requireVariantId(db, k); } catch (Exception e) { throw new RuntimeException(e); }
            });
            int cid = channelIds.computeIfAbsent(channel, k -> {
                try { return requireChannelId(db, k); } catch (Exception e) { throw new RuntimeException(e); }
            });
            db.upsertOrder(o.orderNo, o.date + " " + o.time, vid, cid, o.color, o.listPrice, o.paid);
            inserted++;
            String key = o.date + "|" + variant + "|" + channel;
            Double cur = dailyMin.get(key);
            if (cur == null || o.paid < cur) dailyMin.put(key, o.paid);
            days.add(o.date);
        }

        int filled = 0;
        if (cfg.tuanBackfill) {
            String today = LocalDate.now().toString();
            for (Map.Entry<String, Double> e : dailyMin.entrySet()) {
                String[] parts = e.getKey().split("\\|");
                if (parts[0].equals(today)) continue;   // 当天以报价表数据为准
                int vid = variantIds.get(parts[1]);
                int cid = channelIds.get(parts[2]);
                db.upsertPrice(vid, cid, parts[0], (int) Math.round(e.getValue()), true,
                        "当日最低成交（" + parts[1] + "）", "crawler-tuan-orders");
                filled++;
            }
        }
        System.out.println("[crawler] 成交记录入库 " + inserted + " 条，回填历史价格 " + filled +
                " 条（覆盖 " + days.size() + " 天）");
        return inserted + filled;
    }

    private static void writeLog(Config cfg, String status, String msg, int written) {
        try (Db d = new Db(cfg)) {
            d.update("INSERT INTO " + FETCH_LOG + " (status, message, records) VALUES (?,?,?)", status, msg, written);
        } catch (Exception ignore) {
            System.err.println("[warn] fetch_log 写入失败（不影响主流程）");
        }
    }

    static int requireVariantId(Db db, String code) throws Exception {
        List<Map<String, Object>> rows = db.query("SELECT id FROM " + VARIANT + " WHERE code=?", code);
        if (rows.isEmpty()) throw new IllegalStateException("variant 不存在: " + code);
        return ((Number) rows.get(0).get("id")).intValue();
    }

    static int requireChannelId(Db db, String code) throws Exception {
        List<Map<String, Object>> rows = db.query("SELECT id FROM " + CHANNEL + " WHERE code=?", code);
        if (rows.isEmpty()) throw new IllegalStateException("channel 不存在: " + code);
        return ((Number) rows.get(0).get("id")).intValue();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
