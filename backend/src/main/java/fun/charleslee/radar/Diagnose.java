package fun.charleslee.radar;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网络与解析诊断：java -jar price-radar-crawler.jar --diagnose config.properties
 * 分四层定位问题：DNS 解析 → TCP 连通 → HTTP 响应 → 内容解析。
 * 输出直接指明是「服务器出网受限」还是「页面结构变化」。
 */
public class Diagnose {

    public static void run(String cfgPath) {
        Config cfg;
        try {
            cfg = Config.load(cfgPath);
        } catch (Exception e) {
            System.out.println("[诊断中止] 配置读取失败: " + e.getMessage());
            return;
        }
        String host = hostOf(cfg.tuanUrl);

        System.out.println("=== 1. DNS 解析 ===");
        checkDns(host);

        System.out.println("\n=== 2. TCP 连通（443）===");
        checkTcp(host, 443);

        System.out.println("\n=== 3. HTTP 响应 ===");
        checkHttp(cfg.tuanUrl, cfg);

        System.out.println("\n=== 4. 内容解析 ===");
        try {
            TuanQuoteCheck(cfg);
        } catch (Exception e) {
            System.out.println("  解析失败: " + e.getMessage());
        }

        System.out.println("\n=== 结论参考 ===");
        System.out.println("  DNS/TCP 失败      -> 服务器出网受限（安全组/防火墙/无公网出口）");
        System.out.println("  HTTP 4xx/超时     -> 被拦截，可配 http.proxyHost 走代理");
        System.out.println("  HTTP 200 解析 0 条 -> 对方页面结构变了，需要更新解析规则");
    }

    private static void TuanQuoteCheck(Config cfg) throws Exception {
        String html = Http.get(cfg.tuanUrl, cfg, null);
        int rows = countOccurrences(html, "18 Pro");
        int orders = countMatches(html.replaceAll("<[^>]+>", "|").replaceAll("\\s+", ""), "订单号：\\d{16}");
        System.out.println("  页面 " + html.length() + " 字节；含 '18 Pro' " + rows + " 处；成交记录 " + orders + " 条");
        if (rows == 0 && orders == 0) {
            System.out.println("  ⚠ 未找到预期内容，页面结构可能已变化");
        } else {
            System.out.println("  ✓ 内容正常，抓取可用");
        }
    }

    private static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private static void checkDns(String host) {
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            System.out.println("  " + host + " -> " + addrs[0].getHostAddress() + "  [OK]");
        } catch (Exception e) {
            System.out.println("  " + host + " -> 解析失败: " + e.getMessage() + "  [FAIL]");
        }
    }

    private static void checkTcp(String host, int port) {
        long t0 = System.currentTimeMillis();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 5000);
            System.out.println("  " + host + ":" + port + " -> 连通（" + (System.currentTimeMillis() - t0) + "ms）  [OK]");
        } catch (Exception e) {
            System.out.println("  " + host + ":" + port + " -> 连接失败: " + e.getMessage() + "  [FAIL]");
        }
    }

    private static void checkHttp(String url, Config cfg) {
        long t0 = System.currentTimeMillis();
        try {
            String body = Http.get(url, cfg, null);
            System.out.println("  " + url + "\n    -> HTTP 200，" + body.length() + " 字节，" +
                    (System.currentTimeMillis() - t0) + "ms  [OK]");
        } catch (Exception e) {
            System.out.println("  " + url + "\n    -> 失败: " + e.getMessage() + "  [FAIL]");
        }
    }

    private static int countOccurrences(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    private static int countMatches(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
