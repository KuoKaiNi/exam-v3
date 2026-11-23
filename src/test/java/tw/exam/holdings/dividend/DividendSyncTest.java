package tw.exam.holdings.dividend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tw.exam.holdings.holding.HoldingRepository;
import tw.exam.holdings.holding.TradeRepository;
import tw.exam.holdings.ledger.Side;
import tw.exam.holdings.portfolio.PortfolioService;
import tw.exam.holdings.portfolio.Position;
import tw.exam.holdings.quote.Market;

/** 除權息同步：主機換成本機假伺服器（路徑跟官方一樣），回存下來的官方資料。 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
class DividendSyncTest {

    static final List<String> requests = new CopyOnWriteArrayList<>();
    static HttpServer server;

    static {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", DividendSyncTest::handle);
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void hosts(DynamicPropertyRegistry r) {
        r.add("quote.twse-base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
        r.add("quote.tpex-base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    static void handle(HttpExchange ex) throws IOException {
        String uri = ex.getRequestURI().toString();
        requests.add(uri);
        String body;
        if (uri.startsWith("/rwd/zh/exRight/TWT49UDetail") && uri.contains("STK_NO=2884&T1=20250722")) {
            body = fixture("twse_detail_2884_20250722.json");
        } else if (uri.startsWith("/rwd/zh/exRight/TWT49U?") && uri.contains("startDate=20250101")) {
            body = fixture("twse_twt49u_202506_202507.json");
        } else if (uri.startsWith("/rwd/")) {
            body = "{\"stat\":\"很抱歉，沒有符合條件的資料!\"}";
        } else if (uri.contains("startDate=2025/01/01")) {
            body = fixture("tpex_exdailyq_202506_202507.json");
        } else {
            body = "{\"tables\":[{\"fields\":[],\"data\":[]}],\"stat\":\"ok\"}";
        }
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    static String fixture(String name) throws IOException {
        return ExRightParserTest.fixture(name);
    }

    @Autowired
    ExRightService service;
    @Autowired
    HoldingRepository holdings;
    @Autowired
    TradeRepository trades;
    @Autowired
    PortfolioService portfolio;
    @Autowired
    JdbcClient jdbc;

    @Test
    void syncYearsThenDetailsThenComputeDividends() {
        long esun = holdings.insert("2884", "玉山金", Market.TWSE);
        trades.insert(esun, Side.BUY, LocalDate.of(2025, 7, 1), 2000, new BigDecimal("30.00"));
        long eink = holdings.insert("8069", "元太", Market.TPEX);
        trades.insert(eink, Side.BUY, LocalDate.of(2025, 7, 1), 1000, new BigDecimal("200"));

        Position before = portfolio.position(esun);
        assertThat(before.dividendDataComplete()).isFalse();

        service.sync();

        // 按年抓：2025 到今年，上市、上櫃各一次；玉山金是「權息」，另外查明細
        assertThat(requests).anyMatch(u -> u.startsWith("/rwd/zh/exRight/TWT49U?startDate=20250101&endDate=20251231&response=json"));
        assertThat(requests).anyMatch(u -> u.startsWith("/www/zh-tw/bulletin/exDailyQ?startDate=2025/01/01&endDate=2025/12/31&response=json"));
        assertThat(requests).anyMatch(u -> u.equals("/rwd/zh/exRight/TWT49UDetail?STK_NO=2884&T1=20250722&response=json"));

        Position p = portfolio.position(esun);
        assertThat(p.dividendDataComplete()).isTrue();
        assertThat(p.ledger().cashDividends()).isEqualTo(2400);
        assertThat(p.ledger().stockDividendShares()).isEqualTo(20);
        assertThat(p.ledger().avgCost()).isEqualByComparingTo("29.75");
        assertThat(portfolio.position(eink).ledger().cashDividends()).isEqualTo(4990);

        // 過去的年份、已經拿到的明細不再抓
        int n = requests.size();
        service.sync();
        assertThat(requests.subList(n, requests.size())).noneMatch(u -> u.contains("startDate=20250101") || u.contains("TWT49UDetail"));
    }
}
