package fun.charleslee.radar;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Properties;

/** 配置加载：config.properties 只存服务器本地，不入 git。 */
public class Config {
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

    private Config(Properties p) {
        dbUrl = req(p, "db.url");
        dbUser = req(p, "db.user");
        dbPassword = req(p, "db.password");
        outputJsonPath = p.getProperty("output.json", "/opt/data/charleslee/github/june/data/prices.json");
        launchDate = p.getProperty("meta.launchDate", "2026-09-18");
        httpTimeoutMs = Integer.parseInt(p.getProperty("http.timeoutMs", "10000"));
        httpRetries = Integer.parseInt(p.getProperty("http.retries", "3"));
        proxyHost = p.getProperty("http.proxyHost", "");
        proxyPort = Integer.parseInt(p.getProperty("http.proxyPort", "0"));
        dumpDir = p.getProperty("http.dumpDir", "/opt/data/charleslee/radar/dump");

        tuanEnabled = Boolean.parseBoolean(p.getProperty("tuan.enabled", "true"));
        tuanUrl = p.getProperty("tuan.url", "https://appletuan.com/quotes/1");
        tuanOrderPages = Integer.parseInt(p.getProperty("tuan.orderPages", "2"));
        tuanBackfill = Boolean.parseBoolean(p.getProperty("tuan.backfill", "true"));

        jdEnabled = Boolean.parseBoolean(p.getProperty("jd.enabled", "false"));
        jdSkus = new Properties();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("jd.sku.")) jdSkus.setProperty(key.substring(7), p.getProperty(key).trim());
        }

        adminHost = p.getProperty("admin.host", "127.0.0.1");
        adminPort = Integer.parseInt(p.getProperty("admin.port", "8099"));
        adminUser = p.getProperty("admin.user", "admin");
        adminPassword = p.getProperty("admin.password", "");
    }

    private static String req(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少必需配置项: " + key);
        }
        return v.trim();
    }

    public static Config load(String path) throws IOException {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(path)) {
            p.load(new InputStreamReader(in, "UTF-8"));
        }
        return new Config(p);
    }
}
