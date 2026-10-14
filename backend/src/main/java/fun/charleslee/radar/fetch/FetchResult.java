package fun.charleslee.radar.fetch;

/** 一次抓取到的单条价格。 */
public class FetchResult {
    public final String variantCode;
    public final String channelCode;
    public final int price;
    public final boolean inStock;
    public final String promo;
    public final String source;

    public FetchResult(String variantCode, String channelCode, int price,
                       boolean inStock, String promo, String source) {
        this.variantCode = variantCode;
        this.channelCode = channelCode;
        this.price = price;
        this.inStock = inStock;
        this.promo = promo;
        this.source = source;
    }
}
