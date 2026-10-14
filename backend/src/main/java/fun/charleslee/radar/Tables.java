package fun.charleslee.radar;

/**
 * 表名集中定义（统一前缀），避免 SQL 里散落硬编码表名。
 *
 * <p>换前缀只需改三处，其它地方跟着自动生效：
 * <ol>
 *   <li>本文件 {@link #PFX}</li>
 *   <li>{@code database/schema.sql}</li>
 *   <li>{@code database/seed.sql}</li>
 * </ol>
 * 一行命令即可批量替换 SQL：
 * <pre>perl -pi -e 's/\bradar_/新前缀_/g' database/schema.sql database/seed.sql</pre>
 *
 * <p>前缀取 {@code radar_}：与包名 {@code fun.charleslee.radar}、服务器目录
 * {@code /opt/data/charleslee/radar}、jar 名 {@code price-radar-crawler} 保持一致。
 */
public final class Tables {

    /** 统一表前缀。 */
    public static final String PFX = "radar_";

    /** 机型配置。 */
    public static final String VARIANT = PFX + "variant";
    /** 销售渠道。 */
    public static final String CHANNEL = PFX + "channel";
    /** 价格历史快照（核心表）。 */
    public static final String PRICE_RECORD = PFX + "price_record";
    /** 行情大事记。 */
    public static final String MARKET_EVENT = PFX + "market_event";
    /** 定时任务运行日志。 */
    public static final String FETCH_LOG = PFX + "fetch_log";
    /** 苹果团真实成交记录。 */
    public static final String TUAN_ORDER = PFX + "tuan_order";

    private Tables() {
    }
}
