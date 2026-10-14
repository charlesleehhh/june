package fun.charleslee.radar;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Properties;

/**
 * 配置加载：config.properties 只存服务器本地，不入 git。
 *
 * <p>容错约定：**留空 = 使用默认值**（如 {@code http.proxyPort=} 表示不用代理）。
 * 这是刻意的设计——模板里大量给出"留空即可"的项，若留空就崩会非常难排查。
 * 只有当值存在且不是合法整数时才报错，且错误信息会点名是哪一个配置项。
 */
public class Config {
    /** 实际使用的配置文件路径（排错时先看这个）。 */
    public final String sourcePath;

    public final String dbUrl;
    public final String dbUser;
    public final String dbPassword;
    public final String outputJsonPath;
    public final String launchDate;
    public final int httpTimeoutMs;
    public final int httpRetries;
    public final String proxyHost;
    public final int proxyPort;
    public final String dumpDir;

    /** 苹果团（核心数据源） */
    public final boolean tuanEnabled;
    public final String tuanUrl;
    public final int tuanOrderPages;
    public final boolean tuanBackfill;

    /** 京东（接口已封，默认关闭；若日后恢复可打开） */
    public final boolean jdEnabled;
    public final Properties jdSkus;

    /** 管理员后台（--serve 模式） */
    public final String adminHost;
    public final int adminPort;
    public final String adminUser;
    public final String adminPassword;

    private Config(Properties p, String path) {
        sourcePath = path;

        dbUrl = req(p, "db.url");
        dbUser = req(p, "db.user");
        dbPassword = req(p, "db.password");
        outputJsonPath = str(p, "output.json", "/opt/data/charleslee/github/june/data/prices.json");
        launchDate = str(p, "meta.launchDate", "2026-09-18");
        httpTimeoutMs = num(p, "http.timeoutMs", 10000, 500, 600000);
        httpRetries = num(p, "http.retries", 3, 0, 10);
        proxyHost = str(p, "http.proxyHost", "");
        proxyPort = num(p, "http.proxyPort", 0, 0, 65535);
        dumpDir = str(p, "http.dumpDir", "/opt/data/charleslee/radar/dump");

        tuanEnabled = bool(p, "tuan.enabled", true);
        tuanUrl = str(p, "tuan.url", "https://appletuan.com/quotes/1");
        tuanOrderPages = num(p, "tuan.orderPages", 2, 1, 20);
        tuanBackfill = bool(p, "tuan.backfill", true);

        jdEnabled = bool(p, "jd.enabled", false);
        jdSkus = new Properties();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("jd.sku.")) jdSkus.setProperty(key.substring(7), p.getProperty(key).trim());
        }

        adminHost = str(p, "admin.host", "127.0.0.1");
        adminPort = num(p, "admin.port", 8099, 1, 65535);
        adminUser = str(p, "admin.user", "admin");
        adminPassword = p.getProperty("admin.password", "").trim();   // 允许留空 = 不启用程序内校验
    }

    /** 必需项：缺失或留空时直接报错并点名。 */
    private static String req(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少必需配置项: " + key);
        }
        return v.trim();
    }

    /** 可选项：缺失或留空都取默认值。 */
    private static String str(Properties p, String key, String def) {
        String v = p.getProperty(key);
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    /** 整数项：缺失或留空取默认值；写了非法值则报明确错误（不打印堆栈）。 */
    private static int num(Properties p, String key, int def, int min, int max) {
        String v = p.getProperty(key);
        if (v == null || v.trim().isEmpty()) return def;
        String s = v.trim();
        int n;
        try {
            n = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "配置项 " + key + " 必须是整数，当前写的是 \"" + s + "\"（留空则用默认值 " + def + "）");
        }
        if (n < min || n > max) {
            throw new IllegalArgumentException(
                    "配置项 " + key + " 超出合理范围 " + min + "~" + max + "，当前值 " + n);
        }
        return n;
    }

    /** 布尔项：缺失或留空取默认值；只有 "true"（忽略大小写）为真。 */
    private static boolean bool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return (v == null || v.trim().isEmpty()) ? def : Boolean.parseBoolean(v.trim());
    }

    public static Config load(String path) throws IOException {
        Properties p = new Properties();
        File f = new File(path);
        if (!f.isFile()) {
            throw new IllegalArgumentException("配置文件不存在或无法读取: " + f.getAbsolutePath()
                    + "（当前目录 " + new File(".").getAbsolutePath() + "）");
        }
        try (InputStream in = new FileInputStream(f)) {
            p.load(new InputStreamReader(in, "UTF-8"));
        }
        return new Config(p, f.getAbsolutePath());
    }

    /** 生效配置摘要（不含任何密码），启动时打印，出问题一眼能对上。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("[config] 文件: ").append(sourcePath).append('\n');
        sb.append("[config] 数据库: ").append(maskUrl(dbUrl)).append("  用户: ").append(dbUser).append('\n');
        sb.append("[config] 输出: ").append(outputJsonPath).append('\n');
        sb.append("[config] 数据源: tuan=").append(tuanEnabled ? "on" : "off")
          .append("(orderPages=").append(tuanOrderPages).append(", backfill=").append(tuanBackfill).append(')')
          .append(" jd=").append(jdEnabled ? "on" : "off").append('\n');
        sb.append("[config] 网络: timeout=").append(httpTimeoutMs).append("ms retries=").append(httpRetries)
          .append(" proxy=").append(proxyHost.isEmpty() ? "(未设置)" : proxyHost + ":" + proxyPort).append('\n');
        sb.append("[config] 后台: ").append(adminHost).append(':').append(adminPort)
          .append(adminPassword.isEmpty() ? "（未启用程序内密码）" : "（已启用程序内密码）");
        return sb.toString();
    }

    /** 去掉 JDBC URL 里可能内嵌的账号密码，避免日志泄露。 */
    private static String maskUrl(String url) {
        return url == null ? "" : url.replaceAll("//[^/@]*@", "//***@");
    }
}
