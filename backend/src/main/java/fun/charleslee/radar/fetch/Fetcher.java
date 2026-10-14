package fun.charleslee.radar.fetch;

import fun.charleslee.radar.Config;

import java.util.List;

/** 渠道抓取器接口。抓取失败抛异常即可，Main 会记录到 fetch_log 并继续其它渠道。 */
public interface Fetcher {
    String channelCode();
    List<FetchResult> fetch(Config cfg) throws Exception;
}
