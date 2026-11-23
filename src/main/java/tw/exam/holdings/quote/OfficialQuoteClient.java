package tw.exam.holdings.quote;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 呼叫證交所、櫃買中心。
 *
 * <p>證交所會擋短時間大量查詢，被擋後有一段時間都查不到，所以：
 * <ol>
 *   <li>同一個網站兩次請求之間至少隔 {@code minInterval}（排隊，不是丟掉）；</li>
 *   <li>看到像被擋的回應（403、429、轉址、回 HTML）就暫停查那個網站 {@code blockedBackoff}，免得越查越久。</li>
 * </ol>
 */
@Component
public class OfficialQuoteClient {

    private static final Logger log = LoggerFactory.getLogger(OfficialQuoteClient.class);

    private final QuoteProperties props;
    private final Clock clock;
    private final HttpClient http;
    private final Map<Market, Long> nextSlotNanos = new EnumMap<>(Market.class);
    private final Map<Market, Instant> blockedUntil = new EnumMap<>(Market.class);

    public OfficialQuoteClient(QuoteProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 查某檔某個月的日成交資料。
     *
     * @param deadline 超過這個時間點還排不到就直接回 FAILED（新增持股有 10 秒限制）
     */
    public MonthData fetchMonth(Market market, String code, YearMonth month, Instant deadline) {
        Fetched f = get(market, path(market, code, month), deadline);
        if (!f.ok()) {
            return MonthData.failed(f.failure());
        }
        MonthData data = market == Market.TWSE
                ? OfficialResponseParser.parseTwse(f.body(), code)
                : OfficialResponseParser.parseTpex(f.body(), code);
        log.info("查 {} {} {}：{}", market, code, month, data.status());
        return data;
    }

    /** 一次 GET 的結果；ok 為 false 時 failure 是原因。 */
    public record Fetched(boolean ok, String body, String failure) {
    }

    /**
     * 向證交所或櫃買中心送 GET，套用節流與「被擋就暫停」。
     *
     * @param pathAndQuery 以 / 開頭的路徑與參數，主機部分由設定決定
     */
    public Fetched get(Market market, String pathAndQuery, Instant deadline) {
        return get(market, pathAndQuery, deadline, props.requestTimeout());
    }

    /** 除權息整年的表比較大（約 1 MB），可以給比較長的逾時。 */
    public Fetched get(Market market, String pathAndQuery, Instant deadline, Duration requestTimeout) {
        Instant blocked = blockedUntil(market);
        if (blocked != null && clock.instant().isBefore(blocked)) {
            return new Fetched(false, null, market.label() + "暫停查詢到 " + blocked + "（先前疑似被擋）");
        }
        if (!waitForSlot(market, deadline)) {
            return new Fetched(false, null, market.label() + "排隊超過時限");
        }
        URI uri = URI.create(props.baseUrl(market) + pathAndQuery);
        Duration timeout = requestTimeout;
        Duration left = Duration.between(clock.instant(), deadline);
        if (left.compareTo(timeout) < 0) {
            timeout = left.isNegative() || left.isZero() ? Duration.ofMillis(1) : left;
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (holdings; +https://github.com/KuoKaiNi/exam-v3)")
                .GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            String body = response.body();
            if (status == 403 || status == 429 || (status >= 300 && status < 400) || looksLikeHtml(body)) {
                markBlocked(market, "HTTP " + status);
                return new Fetched(false, null, market.label() + "疑似擋查詢（HTTP " + status + "）");
            }
            if (status != 200) {
                log.warn("{} {} 回 HTTP {}", market, uri, status);
                return new Fetched(false, null, market.label() + "回 HTTP " + status);
            }
            return new Fetched(true, body, null);
        } catch (IOException e) {
            log.warn("{} {} 連線失敗：{}", market, uri, e.toString());
            return new Fetched(false, null, market.label() + "連不上：" + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Fetched(false, null, "查詢被中斷");
        }
    }

    /** 路徑與參數照題目給的官方網址，只有主機部分可設定。 */
    static String path(Market market, String code, YearMonth month) {
        if (market == Market.TWSE) {
            return "/rwd/zh/afterTrading/STOCK_DAY?date="
                    + String.format("%04d%02d01", month.getYear(), month.getMonthValue())
                    + "&stockNo=" + code + "&response=json";
        }
        return "/www/zh-tw/afterTrading/tradingStock?code=" + code
                + "&date=" + String.format("%04d/%02d/01", month.getYear(), month.getMonthValue())
                + "&response=json";
    }

    private boolean waitForSlot(Market market, Instant deadline) {
        long slot;
        long interval = props.minInterval(market).toNanos();
        synchronized (nextSlotNanos) {
            long now = System.nanoTime();
            slot = Math.max(now, nextSlotNanos.getOrDefault(market, now));
            long waitMillis = (slot - now) / 1_000_000;
            if (clock.instant().plusMillis(waitMillis).isAfter(deadline)) {
                return false;
            }
            nextSlotNanos.put(market, slot + interval);
        }
        long sleep = slot - System.nanoTime();
        if (sleep > 0) {
            try {
                Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private Instant blockedUntil(Market market) {
        synchronized (blockedUntil) {
            return blockedUntil.get(market);
        }
    }

    private void markBlocked(Market market, String why) {
        Instant until = clock.instant().plus(props.blockedBackoff());
        synchronized (blockedUntil) {
            blockedUntil.put(market, until);
        }
        log.warn("{} 疑似擋查詢（{}），暫停查詢到 {}", market, why, until);
    }

    private static boolean looksLikeHtml(String body) {
        if (body == null) {
            return false;
        }
        String head = body.stripLeading();
        return head.startsWith("<");
    }
}
