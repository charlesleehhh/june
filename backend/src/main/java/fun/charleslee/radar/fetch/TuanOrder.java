package fun.charleslee.radar.fetch;

/** 苹果团成交记录（一条真实订单）。 */
public class TuanOrder {
    public final String orderNo;
    public final String date;      // yyyy-MM-dd
    public final String time;      // HH:mm:ss
    public final String storage;   // 256G/512G/1T/2T
    public final String color;
    public final String region;    // 国行/港行
    public final String model;     // iPhone18ProMax
    public final int listPrice;    // 官网价
    public final double paid;      // 实付价

    public TuanOrder(String orderNo, String date, String time, String storage, String color,
                     String region, String model, int listPrice, double paid) {
        this.orderNo = orderNo;
        this.date = date;
        this.time = time;
        this.storage = storage;
        this.color = color;
        this.region = region;
        this.model = model;
        this.listPrice = listPrice;
        this.paid = paid;
    }

    /** 映射到库里的 variant code，非 18 Pro 系列返回 null。 */
    public String variantCode() {
        String m = model.toLowerCase();
        if (!m.contains("18pro")) return null;
        boolean max = m.contains("max");
        String gb = TuanFetcher.storageToGb(storage);
        if (gb == null) return null;
        return (max ? "max-" : "pro-") + gb;
    }

    public String channelCode() {
        return region.contains("港") ? TuanFetcher.CH_HK : TuanFetcher.CH_CN;
    }
}
