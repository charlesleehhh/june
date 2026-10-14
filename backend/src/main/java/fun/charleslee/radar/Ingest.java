package fun.charleslee.radar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;

import static fun.charleslee.radar.Tables.FETCH_LOG;

/**
 * 离线导入：把手工整理的价格写进数据库并重新生成 JSON。
 * 用法：
 *   java -jar price-radar-crawler.jar --ingest prices.csv config.properties
 *   java -jar price-radar-crawler.jar --ingest prices.json config.properties
 *
 * CSV 格式（表头可选，列顺序固定）：
 *   variant,channel,date,price,promo,source
 *   pro-256,pdd,,8999,百亿补贴,手工录入        <- date 留空表示今天
 * JSON 格式：与前端 records 一致的数组
 *   [{"variant":"pro-256","channel":"pdd","date":"2026-10-14","price":8999,"promo":"百亿补贴"}]
 */
public class Ingest {

    public static void run(String file, String cfgPath) throws Exception {
        Config cfg = Config.load(cfgPath);
        String content = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
        int ok = 0, skip = 0;

        try (Db db = new Db(cfg)) {
            if (file.toLowerCase().endsWith(".json")) {
                com.fasterxml.jackson.databind.JsonNode arr =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(content);
                for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                    String variant = n.path("variant").asText("");
                    String channel = n.path("channel").asText("");
                    String date = n.hasNonNull("date") ? n.get("date").asText() : LocalDate.now().toString();
                    int price = n.path("price").asInt(0);
                    String promo = n.hasNonNull("promo") ? n.get("promo").asText() : null;
                    String source = n.hasNonNull("source") ? n.get("source").asText() : "离线导入";
                    if (insert(db, variant, channel, date, price, promo, source)) ok++; else skip++;
                }
            } else {
                List<String> lines = Files.readAllLines(Paths.get(file), StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] c = line.split(",");
                    if (i == 0 && line.toLowerCase().startsWith("variant")) continue; // 表头
                    if (c.length < 4) { skip++; continue; }
                    String variant = c[0].trim();
                    String channel = c[1].trim();
                    String date = c[2].trim().isEmpty() ? LocalDate.now().toString() : c[2].trim();
                    int price;
                    try { price = Integer.parseInt(c[3].trim()); } catch (NumberFormatException e) { skip++; continue; }
                    String promo = c.length > 4 && !c[4].trim().isEmpty() ? c[4].trim() : null;
                    String source = c.length > 5 && !c[5].trim().isEmpty() ? c[5].trim() : "离线导入";
                    if (insert(db, variant, channel, date, price, promo, source)) ok++; else skip++;
                }
            }

            if (ok > 0) {
                JsonGenerator.generate(db, cfg);
                System.out.println("[ingest] 写入 " + ok + " 条，跳过 " + skip + " 条，已重新生成 " + cfg.outputJsonPath);
            } else {
                System.out.println("[ingest] 没有可用记录（写入 0，跳过 " + skip + "）");
            }
            db.update("INSERT INTO " + FETCH_LOG + " (status, message, records) VALUES (?,?,?)",
                    ok > 0 ? "OK" : "PARTIAL", "离线导入 " + file + "：成功 " + ok + " 跳过 " + skip, ok);
        }
    }

    private static boolean insert(Db db, String variant, String channel, String date,
                                  int price, String promo, String source) {
        try {
            if (price <= 0 || variant.isEmpty() || channel.isEmpty()) return false;
            db.upsertPrice(Main.requireVariantId(db, variant), Main.requireChannelId(db, channel),
                    date, price, true, promo, source);
            return true;
        } catch (Exception e) {
            System.out.println("[ingest] 跳过 " + variant + "/" + channel + ": " + e.getMessage());
            return false;
        }
    }
}
