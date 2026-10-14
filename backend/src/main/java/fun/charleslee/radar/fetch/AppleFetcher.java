package fun.charleslee.radar.fetch;

import fun.charleslee.radar.Config;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apple 官网抓取（apple.com.cn 购买页）。
 * 官网价格本就稳定，此抓取主要用于校准 official_price 变动。
 * 实现：在页面文本中找容量标记（256GB/1TB...），根据其前最近的系列名判断 pro/max，
 * 取其后最近的一个整百价格作为官网价。
 * 属于尽力而为：页面结构变化时抓取失败，会记录 fetch_log，不影响其它渠道。
 */
public class AppleFetcher implements Fetcher {

    private static final Pattern PRICE = Pattern.compile("[¥￥]\\s?([0-9]{1,2},)?[0-9]{3,4}");
    private static final String[] STORAGES = {"256GB", "512GB", "1TB", "2TB"};

    @Override
    public String channelCode() { return "apple"; }

    @Override
    public List<FetchResult> fetch(Config cfg) throws Exception {
        Document doc = Jsoup.connect(cfg.applePageUrl)
                .timeout(cfg.httpTimeoutMs)
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Safari/537.36")
                .get();
        String text = doc.body().text().replaceAll("\\s+", " ");

        List<FetchResult> out = new ArrayList<>();
        for (String storage : STORAGES) {
            Integer pro = findOfficial(text, storage, false);
            Integer max = findOfficial(text, storage, true);
            if (pro != null) out.add(new FetchResult("pro-" + gbCode(storage), "apple", pro, true, "官网价", "crawler-apple"));
            if (max != null) out.add(new FetchResult("max-" + gbCode(storage), "apple", max, true, "官网价", "crawler-apple"));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("apple 页面未解析出任何价格（结构可能已变化），URL: " + cfg.applePageUrl);
        }
        return out;
    }

    /** gbCode: 256GB->256, 512GB->512, 1TB->1024, 2TB->2048 */
    private static String gbCode(String storage) {
        if (storage.equals("1TB")) return "1024";
        if (storage.equals("2TB")) return "2048";
        return storage.replace("GB", "");
    }

    /**
     * 在 isMax 系列的上下文里找 storage 容量标记后最近的一个整百价格。
     * 系列归属：取容量标记之前最近的 "Pro Max" / "iPhone 18 Pro" 出现位置来判断该区块属于哪个系列。
     */
    private Integer findOfficial(String text, String storage, boolean isMax) {
        int from = 0;
        while (true) {
            int at = text.indexOf(storage, from);
            if (at < 0) return null;
            String before = text.substring(Math.max(0, at - 500), at);
            boolean thisIsMax = before.lastIndexOf("Pro Max") > before.lastIndexOf("iPhone 18 Pro");
            if (thisIsMax == isMax) {
                String window = text.substring(at, Math.min(text.length(), at + 400));
                Matcher m = PRICE.matcher(window);
                while (m.find()) {
                    int price = Integer.parseInt(m.group(0).replaceAll("[^0-9]", ""));
                    if (price % 100 == 0 && price > 5000) return price;
                }
            }
            from = at + storage.length();
        }
    }
}
