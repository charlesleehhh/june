package fun.charleslee.radar.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.charleslee.radar.Config;
import fun.charleslee.radar.Http;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 京东价格抓取。两条路互为备份：
 *   1) 公开价格接口 https://p.3.cn/prices/mgets?skuIds=J_<skuId>
 *   2) 商品页内嵌价格（page 中的 "p":"8999.00" / J-p-<sku> 区块）
 * skuId 在商品页 URL 里：https://item.jd.com/100012043978.html -> 100012043978
 */
public class JdFetcher implements Fetcher {

    private static final Pattern P_JSON = Pattern.compile("\"p\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9]+)?)\"?");
    private static final Pattern P_HTML = Pattern.compile("J-p-(\\d+)[^>]*>[\\s\\S]{0,200}?([¥￥])?\\s?([0-9]{3,6}(?:\\.[0-9]{1,2})?)");

    @Override
    public String channelCode() { return "jd"; }

    @Override
    public List<FetchResult> fetch(Config cfg) throws Exception {
        if (cfg.jdSkus.isEmpty()) {
            throw new IllegalStateException("未配置任何 jd.sku.<variant>=<skuId> 映射，跳过");
        }
        List<FetchResult> out = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Map.Entry<Object, Object> e : cfg.jdSkus.entrySet()) {
            String variantCode = ((String) e.getKey()).trim();
            String skuId = ((String) e.getValue()).trim();
            if (skuId.isEmpty()) continue;
            try {
                Integer price = viaPriceApi(cfg, skuId);
                String src = "crawler-jd(price-api " + skuId + ")";
                if (price == null) {
                    price = viaItemPage(cfg, skuId);
                    src = "crawler-jd(item-page " + skuId + ")";
                }
                if (price == null) {
                    errors.add(variantCode + ": 两条路都没取到价格");
                    continue;
                }
                out.add(new FetchResult(variantCode, "jd", price, true, null, src));
            } catch (Exception ex) {
                errors.add(variantCode + ": " + ex.getMessage());
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("jd 全部失败 -> " + String.join(" | ", errors));
        }
        return out;
    }

    private Integer viaPriceApi(Config cfg, String skuId) throws Exception {
        String body = Http.get("https://p.3.cn/prices/mgets?skuIds=J_" + skuId, cfg,
                "https://item.jd.com/" + skuId + ".html");
        JsonNode arr = new ObjectMapper().readTree(body);
        if (!arr.isArray() || arr.size() == 0) return null;
        double p = arr.get(0).path("p").asDouble(-1);
        return p > 0 ? (int) Math.round(p) : null;
    }

    private Integer viaItemPage(Config cfg, String skuId) throws Exception {
        String html = Http.get("https://item.jd.com/" + skuId + ".html", cfg, "https://www.jd.com/");
        Matcher m = P_JSON.matcher(html);
        while (m.find()) {
            double p = Double.parseDouble(m.group(1));
            if (p > 5000) return (int) Math.round(p);
        }
        Matcher h = P_HTML.matcher(html);
        if (h.find()) {
            double p = Double.parseDouble(h.group(3));
            if (p > 5000) return (int) Math.round(p);
        }
        return null;
    }
}
