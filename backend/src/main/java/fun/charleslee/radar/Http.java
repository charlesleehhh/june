package fun.charleslee.radar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 统一 HTTP 客户端：超时、重试、代理、UA、跟随跳转，失败时给出可读原因。 */
public class Http {

    public static String get(String url, Config cfg, String referer) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= cfg.httpRetries; attempt++) {
            try {
                return once(url, cfg, referer);
            } catch (IOException e) {
                last = e;
                System.out.println("[http] 第 " + attempt + " 次失败: " + url + " -> " + e.getMessage());
                if (attempt < cfg.httpRetries) {
                    try { Thread.sleep(1500L * attempt); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("被中断", ie);
                    }
                }
            }
        }
        throw last;
    }

    private static String once(String url, Config cfg, String referer) throws IOException {
        HttpURLConnection conn;
        try {
            URL u = new URL(url);
            if (cfg.proxyHost != null && !cfg.proxyHost.isEmpty()) {
                Proxy proxy = new Proxy(Proxy.Type.HTTP,
                        new InetSocketAddress(cfg.proxyHost, cfg.proxyPort));
                conn = (HttpURLConnection) u.openConnection(proxy);
            } else {
                conn = (HttpURLConnection) u.openConnection();
            }
        } catch (IOException e) {
            throw new IOException("地址/代理不可用: " + e.getMessage(), e);
        }
        conn.setConnectTimeout(cfg.httpTimeoutMs);
        conn.setReadTimeout(cfg.httpTimeoutMs);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
        conn.setRequestProperty("Accept", "text/html,application/json,*/*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        if (referer != null) conn.setRequestProperty("Referer", referer);

        int code = conn.getResponseCode();
        if (code >= 400) {
            throw new IOException("HTTP " + code + "（可能是反爬拦截或需要登录态）");
        }
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** 抓取失败时把响应存盘，便于人工判断页面结构变化还是被拦截。 */
    public static String diagnostics(String url, Config cfg) throws IOException {
        try {
            String body = once(url, cfg, null);
            java.nio.file.Path p = java.nio.file.Paths.get(cfg.dumpDir, "dump-" + System.currentTimeMillis() + ".html");
            java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.write(p, body.getBytes(StandardCharsets.UTF_8));
            return "已保存页面快照到 " + p + "（" + body.length() + " 字节），可在服务器上 head 前几行确认是否被拦截";
        } catch (IOException e) {
            return "连根本连不上: " + e.getMessage() + " —— 属于网络层问题（出网限制/ DNS / 防火墙），不是解析问题";
        }
    }
}
