package fun.charleslee.radar.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.charleslee.radar.Config;
import fun.charleslee.radar.fetch.FetchResult;
import fun.charleslee.radar.fetch.Fetcher;
import org.jsoup.Jsoup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 京东价格抓取：使用公开价格接口 p.3.cn（无需登录、无反爬）。
 * 需要在 config.properties 里配置 jd.sku.<variantCode>=<skuId>。
 * skuId 获取方式：商品页 URL https://item.jd.com/<skuId>.html 中的数字。
 * 注意：自营商品页若失效（接口变动/风控），本渠道会失败并被记录到 fetch_log，不影响其它渠道。
 */
public class JdFetcher implements Fetcher {

    @Override
    public String channelCode() { return "jd"; }

    @Override
    public List<FetchResult> fetch(Config cfg) throws Exception {
        List<FetchResult> out = new ArrayList<>();
        ObjectMapper om = new ObjectMapper();
        for (Map.Entry<Object, Object> e : cfg.jdSkus.entrySet()) {
            String variantCode = (String) e.getKey();
            String skuId = ((String) e.getValue()).trim();
            String url = "https://p.3.cn/prices/mgets?skuIds=J_" + skuId;
            String body = Jsoup.connect(url)
                    .timeout(cfg.httpTimeoutMs)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Safari/537.36")
                    .header("Referer", "https://item.jd.com/" + skuId + ".html")
                    .ignoreContentType(true)
                    .execute().body();
            JsonNode arr = om.readTree(body);
            if (!arr.isArray() || arr.size() == 0) {
                throw new IllegalStateException("jd 返回异常: " + variantCode + " -> " + body);
            }
            JsonNode item = arr.get(0);
            // p=当前价 op=原价 m=划线价
            double p = item.path("p").asDouble(-1);
            if (p <= 0) {
                throw new IllegalStateException("jd 无有效价格: " + variantCode);
            }
            boolean inStock = !"false".equalsIgnoreCase(item.path("stock").asText("true"));
            out.add(new FetchResult(variantCode, "jd", (int) Math.round(p), inStock,
                    null, "crawler-jd(p.3.cn sku:" + skuId + ")"));
        }
        if (out.isEmpty()) throw new IllegalStateException("jd 未配置任何 jd.sku.* 映射");
        return out;
    }
}
