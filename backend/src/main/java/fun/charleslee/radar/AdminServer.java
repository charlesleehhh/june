package fun.charleslee.radar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static fun.charleslee.radar.Tables.CHANNEL;
import static fun.charleslee.radar.Tables.FETCH_LOG;
import static fun.charleslee.radar.Tables.MARKET_EVENT;
import static fun.charleslee.radar.Tables.PRICE_RECORD;
import static fun.charleslee.radar.Tables.VARIANT;

/**
 * 管理员后台（仅管理员可访问）。
 * 启动：java -jar price-radar-crawler.jar --serve config.properties
 *
 * 安全设计（三层）：
 *   1. 只绑定 127.0.0.1，外网无法直连，必须经由 nginx 反代
 *   2. nginx 上配 Basic Auth（建议）+ HTTPS
 *   3. 程序内可选再校验一次 Basic Auth（admin.user/admin.password 非空时生效）
 * 功能：价格录入（写库 + 立即重新生成 prices.json）、大事记维护、导出 CSV。
 */
public class AdminServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void start(Config cfg) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(cfg.adminHost, cfg.adminPort), 0);
        server.createContext("/", ex -> {
            try {
                route(ex, cfg);
            } catch (Exception e) {
                respond(ex, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}", "application/json");
            }
        });
        server.setExecutor(null);
        server.start();
        System.out.println("[admin] 后台已启动: http://" + cfg.adminHost + ":" + cfg.adminPort + "/" +
                (cfg.adminPassword.isEmpty() ? "  (未设程序内密码，请务必用 nginx Basic Auth 保护)" : "  (已启用 Basic Auth)"));
        System.out.println("[admin] 按 Ctrl+C 停止");
    }

    private static void route(HttpExchange ex, Config cfg) throws Exception {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();

        if (!authorized(ex, cfg)) {
            ex.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"guojia-radar-admin\"");
            respond(ex, 401, "需要登录", "text/plain; charset=utf-8");
            return;
        }

        if ("/".equals(path) || "/index.html".equals(path)) {
            respond(ex, 200, readResource("admin.html"), "text/html; charset=utf-8");
        } else if ("/api/state".equals(path)) {
            respond(ex, 200, state(cfg), "application/json; charset=utf-8");
        } else if ("/api/prices".equals(path) && "POST".equals(method)) {
            respond(ex, 200, savePrices(cfg, readBody(ex)), "application/json; charset=utf-8");
        } else if ("/api/events".equals(path) && "POST".equals(method)) {
            respond(ex, 200, saveEvents(cfg, readBody(ex)), "application/json; charset=utf-8");
        } else if ("/api/export.csv".equals(path)) {
            respond(ex, 200, exportCsv(cfg), "text/csv; charset=utf-8");
        } else {
            respond(ex, 404, "not found", "text/plain; charset=utf-8");
        }
    }

    private static boolean authorized(HttpExchange ex, Config cfg) {
        if (cfg.adminPassword == null || cfg.adminPassword.isEmpty()) return true;
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Basic ")) return false;
        try {
            String decoded = new String(Base64.getDecoder().decode(h.substring(6)), StandardCharsets.UTF_8);
            int i = decoded.indexOf(':');
            String user = i < 0 ? decoded : decoded.substring(0, i);
            String pass = i < 0 ? "" : decoded.substring(i + 1);
            return cfg.adminUser.equals(user) && cfg.adminPassword.equals(pass);
        } catch (Exception e) {
            return false;
        }
    }

    /** 返回机型、渠道、各组合最新价，供后台表格预填。 */
    private static String state(Config cfg) throws Exception {
        try (Db db = new Db(cfg)) {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("today", LocalDate.now().toString());

            List<Map<String, Object>> variants = new ArrayList<>();
            for (Map<String, Object> v : db.query("SELECT code, series, storage, official_price FROM " + VARIANT + " WHERE status=1 ORDER BY series, gb")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", v.get("code"));
                m.put("series", v.get("series"));
                m.put("storage", v.get("storage"));
                m.put("official", ((Number) v.get("official_price")).intValue());
                variants.add(m);
            }
            root.put("variants", variants);

            List<Map<String, Object>> channels = new ArrayList<>();
            for (Map<String, Object> c : db.query("SELECT code, name FROM " + CHANNEL + " WHERE status=1 ORDER BY sort")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", c.get("code"));
                m.put("name", c.get("name"));
                channels.add(m);
            }
            root.put("channels", channels);

            // 各组合最新价：一次查完，取每对 (variant, channel) 最新一条
            Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
            for (Map<String, Object> r : db.query(
                    "SELECT v.code AS variant, c.code AS channel, r.trade_date, r.price, r.promo " +
                    "FROM " + PRICE_RECORD + " r JOIN " + VARIANT + " v ON v.id=r.variant_id " +
                    "JOIN " + CHANNEL + " c ON c.id=r.channel_id " +
                    "ORDER BY r.trade_date ASC, r.id ASC")) {
                String key = r.get("variant") + "|" + r.get("channel");
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("price", r.get("price"));
                rec.put("date", r.get("trade_date").toString());
                rec.put("promo", r.get("promo"));
                latest.put(key, rec);
            }
            root.put("latest", latest);

            List<Map<String, Object>> events = new ArrayList<>();
            for (Map<String, Object> e : db.query("SELECT ev_date, kind, title, body FROM " + MARKET_EVENT + " ORDER BY ev_date")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("date", e.get("ev_date").toString());
                m.put("kind", e.get("kind"));
                m.put("title", e.get("title"));
                m.put("body", e.get("body"));
                events.add(m);
            }
            root.put("events", events);
            return JSON.writeValueAsString(root);
        }
    }

    /** 批量写价：写库后立即重新生成 prices.json，线上秒级生效。 */
    private static String savePrices(Config cfg, String body) throws Exception {
        JsonNode arr = JSON.readTree(body);
        int ok = 0, skip = 0;
        try (Db db = new Db(cfg)) {
            for (JsonNode n : arr) {
                String variant = n.path("variant").asText("");
                String channel = n.path("channel").asText("");
                int price = n.path("price").asInt(0);
                String date = n.hasNonNull("date") && !n.get("date").asText().isEmpty()
                        ? n.get("date").asText() : LocalDate.now().toString();
                String promo = n.hasNonNull("promo") ? n.get("promo").asText() : null;
                if (price <= 0 || variant.isEmpty() || channel.isEmpty()) { skip++; continue; }
                try {
                    db.upsertPrice(Main.requireVariantId(db, variant), Main.requireChannelId(db, channel),
                            date, price, true, promo, "admin-web");
                    ok++;
                } catch (Exception e) {
                    skip++;
                }
            }
            JsonGenerator.generate(db, cfg);
            db.update("INSERT INTO " + FETCH_LOG + " (status, message, records) VALUES (?,?,?)",
                    "OK", "后台录入 " + ok + " 条（跳过 " + skip + "）", ok);
        }
        return "{\"ok\":" + ok + ",\"skip\":" + skip + "}";
    }

    /** 大事记整体替换（后台按 JSON 编辑）。 */
    private static String saveEvents(Config cfg, String body) throws Exception {
        JsonNode arr = JSON.readTree(body);
        int n = 0;
        try (Db db = new Db(cfg)) {
            db.update("DELETE FROM " + MARKET_EVENT);
            for (JsonNode e : arr) {
                String date = e.path("date").asText("");
                String title = e.path("title").asText("");
                if (date.isEmpty() || title.isEmpty()) continue;
                db.update("INSERT INTO " + MARKET_EVENT + " (ev_date, kind, title, body) VALUES (?,?,?,?)",
                        date, e.path("kind").asText("milestone"), title, e.path("body").asText(null));
                n++;
            }
            JsonGenerator.generate(db, cfg);
        }
        return "{\"ok\":" + n + "}";
    }

    private static String exportCsv(Config cfg) throws Exception {
        StringBuilder sb = new StringBuilder("variant,channel,date,price,promo,source\n");
        try (Db db = new Db(cfg)) {
            for (Map<String, Object> r : db.query(
                    "SELECT v.code AS variant, c.code AS channel, r.trade_date, r.price, r.promo, r.source " +
                    "FROM " + PRICE_RECORD + " r JOIN " + VARIANT + " v ON v.id=r.variant_id " +
                    "JOIN " + CHANNEL + " c ON c.id=r.channel_id " +
                    "ORDER BY r.trade_date DESC LIMIT 2000")) {
                sb.append(r.get("variant")).append(',').append(r.get("channel")).append(',')
                  .append(r.get("trade_date")).append(',').append(r.get("price")).append(',')
                  .append(r.get("promo") == null ? "" : String.valueOf(r.get("promo")).replace(",", " "))
                  .append(',').append(r.get("source") == null ? "" : r.get("source")).append('\n');
            }
        }
        return sb.toString();
    }

    private static String readResource(String name) throws IOException {
        try (InputStream in = AdminServer.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) return "<h1>admin.html 资源缺失</h1>";
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange ex, int code, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
