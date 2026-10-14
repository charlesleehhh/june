package fun.charleslee.radar;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** 配置加载：config.properties 只存服务器本地，不入 git。 */
public class Config {
    public final String dbUrl;
    public final String dbUser;
    public final String dbPassword;
    public final String outputJsonPath;
    public final String launchDate;
    public final int httpTimeoutMs;
    public final String applePageUrl;
    public final boolean appleEnabled;
    public final boolean jdEnabled;
    /** variantCode -> jd skuId，如 jd.sku.pro-256=100012043978 */
    public final Properties jdSkus;

    private Config(Properties p) {
        dbUrl = req(p, "db.url");
        dbUser = req(p, "db.user");
        dbPassword = req(p, "db.password");
        outputJsonPath = p.getProperty("output.json", "/opt/data/charleslee/github/june/data/prices.json");
        launchDate = p.getProperty("meta.launchDate", "2026-09-18");
        httpTimeoutMs = Integer.parseInt(p.getProperty("http.timeoutMs", "10000"));
        applePageUrl = p.getProperty("apple.url", "https://www.apple.com.cn/shop/buy-iphone/iphone-18-pro");
        appleEnabled = Boolean.parseBoolean(p.getProperty("apple.enabled", "true"));
        jdEnabled = Boolean.parseBoolean(p.getProperty("jd.enabled", "true"));
        jdSkus = new Properties();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("jd.sku.")) jdSkus.setProperty(key.substring(7), p.getProperty(key));
        }
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
            p.load(new java.io.InputStreamReader(in, "UTF-8"));
        }
        return new Config(p);
    }
}
