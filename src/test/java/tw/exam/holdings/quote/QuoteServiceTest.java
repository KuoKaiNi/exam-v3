package tw.exam.holdings.quote;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 用本機的假官方伺服器測查詢流程（不連證交所、櫃買中心）。
 * 同時驗證「主機部分可以換掉、路徑與參數不變」。
 */
class QuoteServiceTest {

    static final ZoneId TPE = ZoneId.of("Asia/Taipei");

    HttpServer server;
    String base;
    /** key：路徑＋查詢字串；value：[HTTP 狀態, 內容]。沒設定的回查無資料。 */
    final Map<String, Object[]> responses = new ConcurrentHashMap<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    volatile long delayMillis = 0;
    /** 只讓證交所慢回 */
    volatile long twseDelayMillis = 0;

    MutableClock clock;
    QuoteService service;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        clock = new MutableClock(ZonedDateTime.of(2026, 11, 23, 15, 0, 0, 0, TPE));
        service = newService(Duration.ofSeconds(8));
    }

    QuoteService newService(Duration lookupTimeout) {
        QuoteProperties props = new QuoteProperties(base, base + "/", Duration.ofSeconds(1), Duration.ofSeconds(2),
                lookupTimeout, Duration.ZERO, Duration.ZERO, Duration.ofMinutes(10),
                Duration.ofMinutes(20), Duration.ofMinutes(5), Duration.ofMinutes(5), false);
        return new QuoteService(new OfficialQuoteClient(props, clock), props, clock, QuoteStore.NONE);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String key = ex.getRequestURI().getRawPath() + "?" + ex.getRequestURI().getRawQuery();
        requests.add(key);
        if (twseDelayMillis > 0 && key.startsWith("/rwd/")) {
            try {
                Thread.sleep(twseDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Object[] r = responses.get(key);
        int status;
        String body;
        if (r != null) {
            status = (int) r[0];
            body = (String) r[1];
        } else if (key.startsWith("/rwd/")) {
            status = 200;
            body = "{\"stat\":\"很抱歉，沒有符合條件的資料!\",\"total\":0}";
        } else {
            status = 200;
            body = tpex("9999", "", "");
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    static String twsePath(String code, String yyyymm) {
        return "/rwd/zh/afterTrading/STOCK_DAY?date=" + yyyymm + "01&stockNo=" + code + "&response=json";
    }

    static String tpexPath(String code, String yyyySlashMm) {
        return "/www/zh-tw/afterTrading/tradingStock?code=" + code + "&date=" + yyyySlashMm + "/01&response=json";
    }

    static String twse(String code, String name, String rows) {
        return "{\"stat\":\"OK\",\"title\":\"115年11月 " + code + " " + name + "           各日成交資訊\","
                + "\"fields\":[\"日期\",\"成交股數\",\"成交金額\",\"開盤價\",\"最高價\",\"最低價\",\"收盤價\",\"漲跌價差\",\"成交筆數\",\"註記\"],"
                + "\"data\":[" + rows + "]}";
    }

    static String twseRow(String rocDate, String close) {
        return "[\"" + rocDate + "\",\"1\",\"1\",\"1\",\"1\",\"1\",\"" + close + "\",\"0\",\"1\",\"\"]";
    }

    static String tpex(String code, String name, String rows) {
        return "{\"tables\":[{\"title\":\"個股日成交資訊\",\"subtitle\":\"" + code + " " + name + " 115年11月\","
                + "\"data\":[" + rows + "],\"fields\":[\"日 期\",\"成交張數\",\"成交仟元\",\"開盤\",\"最高\",\"最低\",\"收盤\",\"漲跌\",\"筆數\"]}],"
                + "\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"stat\":\"ok\"}";
    }

    static String tpexRow(String rocDate, String close) {
        return "[\"" + rocDate + "\",\"1\",\"1\",\"1\",\"1\",\"1\",\"" + close + "\",\"0\",\"1\"]";
    }

    @Test
    void listedStockFoundOnTwseWithConfiguredHost() {
        responses.put(twsePath("2330", "202611"), new Object[] {200,
                twse("2330", "台積電", twseRow("115/11/20", "2,050.00") + "," + twseRow("115/11/23", "2,030.00"))});

        QuoteService.Lookup r = service.lookup("2330");

        assertThat(r).isInstanceOfSatisfying(QuoteService.Lookup.Found.class, f -> {
            assertThat(f.market()).isEqualTo(Market.TWSE);
            assertThat(f.name()).isEqualTo("台積電");
            assertThat(f.quote().close()).isEqualByComparingTo("2030");
            assertThat(f.quote().date()).hasToString("2026-11-23");
        });
        assertThat(service.cached("2330")).isPresent();
    }

    @Test
    void listedStockFoundEvenWhenTwseAnswersAfterTpex() {
        // 實際操作時抓到的 bug：證交所比櫃買中心晚回時，誤判成「官方查不到」
        twseDelayMillis = 300;
        responses.put(twsePath("1101", "202611"), new Object[] {200, twse("1101", "台泥", twseRow("115/11/23", "20.00"))});

        assertThat(service.lookup("1101")).isInstanceOfSatisfying(QuoteService.Lookup.Found.class,
                f -> assertThat(f.market()).isEqualTo(Market.TWSE));
    }

    @Test
    void otcStockFoundOnTpex() {
        responses.put(tpexPath("6488", "2026/11"), new Object[] {200, tpex("6488", "環球晶", tpexRow("115/11/23", "460.50"))});

        assertThat(service.lookup("6488")).isInstanceOfSatisfying(QuoteService.Lookup.Found.class, f -> {
            assertThat(f.market()).isEqualTo(Market.TPEX);
            assertThat(f.name()).isEqualTo("環球晶");
        });
    }

    @Test
    void codeOnNeitherMarketIsNotFound() {
        assertThat(service.lookup("9999")).isInstanceOf(QuoteService.Lookup.NotFound.class);
        // 這個月沒有就再查上個月，兩邊各兩次
        assertThat(requests).containsExactlyInAnyOrder(
                twsePath("9999", "202611"), twsePath("9999", "202610"),
                tpexPath("9999", "2026/11"), tpexPath("9999", "2026/10"));
    }

    @Test
    void firstDayOfMonthBeforeCloseUsesPreviousMonth() {
        clock.set(ZonedDateTime.of(2026, 12, 1, 9, 0, 0, 0, TPE));
        responses.put(twsePath("2330", "202611"), new Object[] {200, twse("2330", "台積電", twseRow("115/11/30", "2,000.00"))});

        assertThat(service.lookup("2330")).isInstanceOfSatisfying(QuoteService.Lookup.Found.class,
                f -> assertThat(f.quote().date()).hasToString("2026-11-30"));
    }

    @Test
    void oneSiteDownMeansUnavailableNotNotFound() {
        responses.put(twsePath("9999", "202611"), new Object[] {500, "oops"});

        assertThat(service.lookup("9999")).isInstanceOf(QuoteService.Lookup.Unavailable.class);
    }

    @Test
    void htmlResponseLooksBlockedAndPausesThatSite() {
        responses.put(twsePath("2330", "202611"), new Object[] {200, "<html>請稍後再試</html>"});

        assertThat(service.lookup("2330")).isInstanceOf(QuoteService.Lookup.Unavailable.class);
        int before = (int) requests.stream().filter(r -> r.startsWith("/rwd/")).count();
        service.lookup("2330");
        int after = (int) requests.stream().filter(r -> r.startsWith("/rwd/")).count();
        assertThat(after).isEqualTo(before);

        clock.advance(Duration.ofMinutes(11));
        service.lookup("2330");
        assertThat(requests.stream().filter(r -> r.startsWith("/rwd/")).count()).isGreaterThan(after);
    }

    @Test
    void slowSiteStillAnswersWithinLookupTimeout() {
        delayMillis = 5_000;
        QuoteService quick = newService(Duration.ofMillis(1500));
        long start = System.nanoTime();
        QuoteService.Lookup r = quick.lookup("2330");
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(r).isInstanceOf(QuoteService.Lookup.Unavailable.class);
        assertThat(millis).isLessThan(4_000);
    }

    @Test
    void todaysCloseShowsUpWithoutRestart() {
        // 13:00 新增：只有上週五（11/20）的收盤價
        clock.set(ZonedDateTime.of(2026, 11, 23, 13, 0, 0, 0, TPE));
        responses.put(twsePath("2330", "202611"), new Object[] {200, twse("2330", "台積電", twseRow("115/11/20", "2,050.00"))});
        service.lookup("2330");
        assertThat(service.cached("2330").get().date()).hasToString("2026-11-20");

        // 13:10 還沒收盤，11/20 就是最新的，不查
        clock.advance(Duration.ofMinutes(10));
        assertThat(service.needsRefresh("2330")).isFalse();

        // 14:00 官方公布今天收盤價
        responses.put(twsePath("2330", "202611"), new Object[] {200,
                twse("2330", "台積電", twseRow("115/11/20", "2,050.00") + "," + twseRow("115/11/23", "2,030.00"))});
        clock.set(ZonedDateTime.of(2026, 11, 23, 14, 0, 0, 0, TPE));
        assertThat(service.needsRefresh("2330")).isTrue();
        service.refreshIfStale("2330", Market.TWSE);
        assertThat(service.cached("2330").get().date()).hasToString("2026-11-23");

        // 拿到今天的就不再查
        long count = twseRequests();
        clock.advance(Duration.ofHours(3));
        service.refreshIfStale("2330", Market.TWSE);
        assertThat(twseRequests()).isEqualTo(count);
    }

    long twseRequests() {
        return requests.stream().filter(r -> r.startsWith("/rwd/")).count();
    }

    @Test
    void staleQuoteIsRetriedAtMostEveryStaleRetry() {
        clock.set(ZonedDateTime.of(2026, 11, 23, 14, 0, 0, 0, TPE));
        responses.put(twsePath("2330", "202611"), new Object[] {200, twse("2330", "台積電", twseRow("115/11/20", "2,050.00"))});
        service.lookup("2330");
        // 新增時上櫃那邊也會同時查，可能還在跑；只數證交所的請求
        long count = twseRequests();
        clock.advance(Duration.ofMinutes(10));
        service.refreshIfStale("2330", Market.TWSE);
        assertThat(twseRequests()).isEqualTo(count);
        clock.advance(Duration.ofMinutes(11));
        service.refreshIfStale("2330", Market.TWSE);
        assertThat(twseRequests()).isEqualTo(count + 1);
    }

    @Test
    void failedRefreshKeepsOldQuote() {
        clock.set(ZonedDateTime.of(2026, 11, 23, 14, 0, 0, 0, TPE));
        responses.put(twsePath("2330", "202611"), new Object[] {200, twse("2330", "台積電", twseRow("115/11/20", "2,050.00"))});
        service.lookup("2330");
        responses.put(twsePath("2330", "202611"), new Object[] {502, "bad gateway"});
        clock.advance(Duration.ofMinutes(30));
        service.refreshIfStale("2330", Market.TWSE);
        assertThat(service.cached("2330").get().close()).isEqualByComparingTo("2050");
    }
}
