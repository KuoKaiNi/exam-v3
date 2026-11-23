package tw.exam.holdings.quote;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 官方行情的設定。主機部分可用環境變數 TWSE_BASE_URL、TPEX_BASE_URL 換掉（見 application.properties）。
 *
 * @param twseBaseUrl       證交所主機，預設 https://www.twse.com.tw
 * @param tpexBaseUrl       櫃買中心主機，預設 https://www.tpex.org.tw
 * @param connectTimeout    連線逾時
 * @param requestTimeout    單次請求逾時
 * @param lookupTimeout     新增持股時查代號的總時限（題目要求 10 秒內看到結果）
 * @param twseMinInterval   兩次查證交所的最短間隔（證交所會擋短時間大量查詢）
 * @param tpexMinInterval   兩次查櫃買中心的最短間隔
 * @param blockedBackoff    疑似被擋（403、429、轉址、回 HTML）後，這段時間內不再查那個網站
 * @param staleRetry        手上的收盤價不是「應該已公布的最近交易日」時，隔多久重查一次
 * @param failedRetry       上次沒查到（連不上）時，隔多久重查一次
 * @param refreshCheck      背景檢查要不要更新的週期
 * @param refreshEnabled    是否開背景更新（測試時關掉）
 */
@ConfigurationProperties(prefix = "quote")
public record QuoteProperties(
        String twseBaseUrl,
        String tpexBaseUrl,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration lookupTimeout,
        Duration twseMinInterval,
        Duration tpexMinInterval,
        Duration blockedBackoff,
        Duration staleRetry,
        Duration failedRetry,
        Duration refreshCheck,
        boolean refreshEnabled) {

    public String baseUrl(Market market) {
        String url = market == Market.TWSE ? twseBaseUrl : tpexBaseUrl;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public Duration minInterval(Market market) {
        return market == Market.TWSE ? twseMinInterval : tpexMinInterval;
    }
}
