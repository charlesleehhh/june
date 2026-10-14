package fun.charleslee.radar;

import fun.charleslee.radar.fetch.FetchResult;
import fun.charleslee.radar.fetch.Fetcher;
import fun.charleslee.radar.fetch.TuanFetcher;
import fun.charleslee.radar.fetch.TuanOrder;

import java.util.List;

/**
 * 只抓取、只打印，不连数据库、不写文件。
 * 用途：在服务器上先验证「网络能不能通、页面能不能解析」，再决定是否跑正式流程。
 *   java -jar price-radar-crawler.jar --dry-run config.properties
 */
public class DryRun {

    public static void run(String cfgPath) {
        Config cfg;
        try {
            cfg = Config.load(cfgPath);
        } catch (Exception e) {
            System.out.println("[dry-run] 配置读取失败: " + e.getMessage());
            return;
        }

        System.out.println("=== 1. 苹果团当日报价 ===");
        try {
            TuanFetcher tuan = new TuanFetcher();
            List<FetchResult> quotes = tuan.fetch(cfg);
            System.out.println("解析到 " + quotes.size() + " 条报价:");
            for (FetchResult r : quotes) {
                System.out.printf("  %-10s %-8s ¥%-7d %s%n",
                        r.variantCode, r.channelCode, r.price, r.promo == null ? "" : r.promo);
            }
        } catch (Exception e) {
            System.out.println("  [FAIL] " + e.getMessage());
        }

        System.out.println("\n=== 2. 苹果团成交记录（历史） ===");
        try {
            List<TuanOrder> orders = new TuanFetcher().fetchOrders(cfg);
            long n18 = orders.stream().filter(o -> o.variantCode() != null).count();
            System.out.println("解析到 " + orders.size() + " 条成交记录，其中 18 Pro 系列 " + n18 + " 条");
            if (!orders.isEmpty()) {
                java.util.List<TuanOrder> sorted = new java.util.ArrayList<>(orders);
                sorted.sort((a, b) -> (a.date + a.time).compareTo(b.date + b.time));
                System.out.println("时间范围: " + sorted.get(0).date + " → " + sorted.get(sorted.size() - 1).date);
                System.out.println("最近 5 条:");
                for (int i = sorted.size() - 1; i >= Math.max(0, sorted.size() - 5); i--) {
                    TuanOrder o = sorted.get(i);
                    System.out.printf("  %s %s  %s/%s/%s/%s 官网%d 实付¥%.2f%n",
                            o.date, o.time, o.storage, o.color, o.region, o.model, o.listPrice, o.paid);
                }
            }
        } catch (Exception e) {
            System.out.println("  [FAIL] " + e.getMessage());
        }
        System.out.println("\n[dry-run] 结束（未连数据库、未写任何文件）");
    }
}
