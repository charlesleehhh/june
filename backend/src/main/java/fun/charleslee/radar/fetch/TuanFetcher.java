package fun.charleslee.radar.fetch;

import fun.charleslee.radar.Config;
import fun.charleslee.radar.Http;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 苹果团（appletuan.com）抓取器 —— 本项目的核心数据源。
 *
 * 为什么依赖它：它是**服务端渲染**的报价页，价格直接写在 HTML 表格里，
 * 且提供两类数据（均由本类解析）：
 *   1) 每日报价表：国行/港行 × 各容量 × 各颜色 → 当天快照（每容量取四色最低价）
 *   2) 成交记录：真实订单（时间/配置/实付价）→ 可回填历史，撑起价格走势图
 *
 * 注意：京东/拼多多/天猫/得物均无法自动获取（已实测），苹果团是少数可稳定获取的公开来源。
 * 抓取频率建议每日 1~3 次，避免给对端造成压力。
 */
public class TuanFetcher implements Fetcher {

    public static final String CH_CN = "tuan-g";
    public static final String CH_HK = "tuan-hk";

    /** 订单号(16位) → 时间 → 容量/颜色/版本/型号(官网价) → ... → 实付金额 */
    private static final Pattern ORDER = Pattern.compile(
            "订单号：(\\d{16})\\*+\\|*(\\d{4}-\\d{2}-\\d{2})(\\d{2}:\\d{2}:\\d{2})\\|+" +
            "([^|]+?)/([^|]+?)/(港行|国行|囯行)/([A-Za-z0-9]+?)\\((\\d+)\\)[\\s\\S]{0,220}?¥([\\d.]+)");

    @Override
    public String channelCode() { return "tuan"; }

    /** 当天快照：每个容量在四种颜色中取最低价（并把最低价对应的颜色写进 promo）。 */
    @Override
    public List<FetchResult> fetch(Config cfg) throws Exception {
        Document doc = Jsoup.parse(Http.get(cfg.tuanUrl, cfg, null));
        Map<String, int[]> best = new LinkedHashMap<>();   // key: variant|channel → [minPrice, colorIdx]
        Map<String, String> bestColor = new LinkedHashMap<>();

        for (Element tr : doc.select("tr")) {
            Elements tds = tr.select("td");
            if (tds.size() < 4) continue;
            String label = tds.get(0).text().trim();
            String region = tds.get(1).text().trim();
            if (!label.contains("18 Pro")) continue;
            // 页面里存在「囯行」这类错别字，用宽松判断
            String channel = region.contains("港") ? CH_HK : (region.contains("行") ? CH_CN : null);
            if (channel == null) continue;

            String variant = variantOf(label);
            if (variant == null) continue;

            for (int i = 2; i < tds.size(); i++) {
                String raw = tds.get(i).text().replaceAll("[^0-9]", "");
                if (raw.isEmpty()) continue;
                int price = Integer.parseInt(raw);
                if (price < 5000 || price > 40000) continue;
                String key = variant + "|" + channel;
                int[] cur = best.get(key);
                if (cur == null || price < cur[0]) {
                    best.put(key, new int[]{price, i});
                    String color = i < tds.size() ? colorName(i) : "";
                    bestColor.put(key, color);
                }
            }
        }

        List<FetchResult> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : best.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            String promo = bestColor.get(e.getKey());
            out.add(new FetchResult(parts[0], parts[1], e.getValue()[0], true,
                    promo.isEmpty() ? "现货时价" : promo + "最低", "crawler-tuan"));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("苹果团页面未解析出 18 Pro 报价（页面结构可能变化）: " + cfg.tuanUrl);
        }
        return out;
    }

    /** 成交记录（含历史），用于回填走势与前端「最近成交」。 */
    public List<TuanOrder> fetchOrders(Config cfg) throws Exception {
        List<TuanOrder> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int page = 1; page <= cfg.tuanOrderPages; page++) {
            String url = cfg.tuanUrl + "?page=" + page;
            String flat;
            try {
                flat = Http.get(url, cfg, cfg.tuanUrl).replaceAll("<[^>]+>", "|").replaceAll("\\s+", "");
            } catch (Exception e) {
                System.out.println("[tuan] 第 " + page + " 页抓取失败: " + e.getMessage());
                continue;
            }
            Matcher m = ORDER.matcher(flat);
            int n = 0;
            while (m.find()) {
                String orderNo = m.group(1);
                if (!seen.add(orderNo + m.group(2))) continue;
                out.add(new TuanOrder(orderNo, m.group(2), m.group(3), m.group(4), m.group(5),
                        m.group(6), m.group(7), Integer.parseInt(m.group(8)),
                        Double.parseDouble(m.group(9))));
                n++;
            }
            System.out.println("[tuan] 第 " + page + " 页成交记录 " + n + " 条");
        }
        return out;
    }

    /** "iPhone 18 Pro 256G" / "...1T" / "...2T" → pro-256 / max-1024 ... */
    private static String variantOf(String label) {
        boolean max = label.toLowerCase().contains("max");
        String storage = label.replaceAll("(?i).*?(\\d+G|\\d+T)", "$1");
        String gb = storageToGb(storage);
        return gb == null ? null : (max ? "max-" : "pro-") + gb;
    }

    static String storageToGb(String storage) {
        String s = storage.trim().toUpperCase();
        if (s.startsWith("256")) return "256";
        if (s.startsWith("512")) return "512";
        if (s.startsWith("1T")) return "1024";
        if (s.startsWith("2T")) return "2048";
        return null;
    }

    /** 报价表第 3~6 列依次为 黑色/银色/勃艮第酒红色/冰川蓝色。 */
    private static String colorName(int colIndex) {
        switch (colIndex) {
            case 2: return "黑色";
            case 3: return "银色";
            case 4: return "勃艮第酒红";
            case 5: return "冰川蓝";
            default: return "";
        }
    }
}
