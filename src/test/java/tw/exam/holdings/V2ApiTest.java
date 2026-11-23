package tw.exam.holdings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import tw.exam.holdings.dividend.ExRightStore;
import tw.exam.holdings.holding.TradeRepository;
import tw.exam.holdings.ledger.ExRight;
import tw.exam.holdings.quote.Market;
import tw.exam.holdings.quote.Quote;
import tw.exam.holdings.quote.QuoteProperties;
import tw.exam.holdings.quote.QuoteService;

/** 2.0 API：交易、冪等重送、版本衝突、賣超、股利跟著交易變，以及 App 1.0 看到的樣子。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V2ApiTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TradeRepository trades;
    @Autowired
    ExRightStore exRights;
    @Autowired
    QuoteProperties props;

    @MockitoBean
    QuoteService quotes;

    final Quote tsmc = new Quote("2330", "台積電", Market.TWSE, LocalDate.of(2026, 11, 20), new BigDecimal("1100.00"));

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM holding").update();
        jdbc.sql("DELETE FROM idempotency_key").update();
        jdbc.sql("DELETE FROM ex_right").update();
        jdbc.sql("DELETE FROM ex_right_fetch").update();
        when(quotes.lookup("2330")).thenReturn(new QuoteService.Lookup.Found(Market.TWSE, "台積電", tsmc));
        when(quotes.cached("2330")).thenReturn(Optional.of(tsmc));
        // 2330 2025-06-12 除息 4.50002
        String src = props.baseUrl(Market.TWSE);
        for (int y = 2024; y <= LocalDate.now().getYear(); y++) {
            exRights.saveYear(src, Market.TWSE, y, y == 2025
                    ? List.of(new ExRight("2330", LocalDate.of(2025, 6, 12), "息", new BigDecimal("4.50002"), BigDecimal.ZERO))
                    : List.of(), Instant.now());
        }
    }

    ResultActions postTrade(String key, String body) throws Exception {
        var req = post("/api/v2/trades").contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            req = req.header("Idempotency-Key", key);
        }
        return mvc.perform(req);
    }

    JsonNode json(ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString());
    }

    static String buy(String date, long shares, String price) {
        return "{\"code\":\"2330\",\"side\":\"BUY\",\"trade_date\":\"" + date + "\",\"shares\":" + shares + ",\"price\":" + price + "}";
    }

    static String sell(String date, long shares, String price) {
        return buy(date, shares, price).replace("BUY", "SELL");
    }

    @Test
    void createTradeAndSeeDividendAndTotalReturn() throws Exception {
        JsonNode r = json(postTrade("k1", buy("2025-06-10", 1500, "1000")).andExpect(status().isCreated()));
        assertThat(r.path("trade").path("version").asInt()).isEqualTo(1);
        long holdingId = r.path("trade").path("holding_id").asLong();
        mvc.perform(get("/api/v2/holdings/" + holdingId))
                .andExpect(jsonPath("$.shares").value(1500))
                .andExpect(jsonPath("$.cash_dividends").value(6750))
                .andExpect(jsonPath("$.dividends[0].ex_date").value("2025-06-12"))
                .andExpect(jsonPath("$.dividends[0].eligible_shares").value(1500))
                .andExpect(jsonPath("$.dividends[0].cash").value(6750))
                .andExpect(jsonPath("$.dividend_data_complete").value(true))
                .andExpect(jsonPath("$.trades[0].trade_date").value("2025-06-10"));
        // 含息總報酬＝0＋未實現＋6,750
        JsonNode h = json(mvc.perform(get("/api/v2/holdings/" + holdingId)));
        assertThat(h.path("total_return").asLong()).isEqualTo(h.path("unrealized_pnl").asLong() + 6750);
    }

    @Test
    void retryWithSameKeyRecordsOnceAndReplays() throws Exception {
        JsonNode first = json(postTrade("retry-1", buy("2025-06-10", 1000, "1000")).andExpect(status().isCreated()));
        JsonNode second = json(postTrade("retry-1", buy("2025-06-10", 1000, "1000"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true")));
        assertThat(second.path("trade").path("id").asLong()).isEqualTo(first.path("trade").path("id").asLong());
        assertThat(trades.findAll()).hasSize(1);
        verify(quotes, times(1)).lookup("2330");
        // 同一個 key 換內容：422
        postTrade("retry-1", buy("2025-06-10", 2000, "1000"))
                .andExpect(status().is(422)).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        // 沒帶 key：照常新增
        postTrade(null, buy("2025-06-10", 1000, "1000")).andExpect(status().isCreated());
        assertThat(trades.findAll()).hasSize(2);
    }

    @Test
    void concurrentRetryWhileFirstStillRunningWaitsAndReplays() throws Exception {
        when(quotes.lookup("2330")).thenAnswer(inv -> {
            Thread.sleep(1500);
            return new QuoteService.Lookup.Found(Market.TWSE, "台積電", tsmc);
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Integer> call = () -> postTrade("slow", buy("2025-06-10", 1000, "1000")).andReturn().getResponse().getStatus();
        Future<Integer> a = pool.submit(call);
        Thread.sleep(300);
        Future<Integer> b = pool.submit(call);
        assertThat(a.get()).isEqualTo(201);
        assertThat(b.get()).isEqualTo(201);
        pool.shutdown();
        assertThat(trades.findAll()).hasSize(1);
    }

    @Test
    void cannotSellMoreThanHeld() throws Exception {
        postTrade("a", buy("2025-06-10", 1000, "1000"));
        postTrade("b", sell("2025-07-01", 1001, "1100"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_SHARES"))
                .andExpect(jsonPath("$.error.message", containsString("當時只持有 1000 股")));
        // 賣在買之前也不行
        postTrade("c", sell("2025-06-01", 100, "1100")).andExpect(status().isConflict());
        // 沒有持股的代號不能賣
        when(quotes.lookup("2317")).thenReturn(new QuoteService.Lookup.Found(Market.TWSE, "鴻海", null));
        postTrade("d", sell("2025-06-01", 100, "100").replace("2330", "2317")).andExpect(status().isConflict());
        assertThat(trades.findAll()).hasSize(1);
    }

    @Test
    void editingDateMovesDividendAndVersionConflicts() throws Exception {
        JsonNode r = json(postTrade("a", buy("2025-06-20", 1000, "1000")));
        long id = r.path("trade").path("id").asLong();
        long hid = r.path("trade").path("holding_id").asLong();
        mvc.perform(get("/api/v2/holdings/" + hid)).andExpect(jsonPath("$.cash_dividends").value(0));
        // 補登：其實是除息前買的
        mvc.perform(put("/api/v2/trades/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"side\":\"BUY\",\"trade_date\":\"2025-06-11\",\"shares\":1000,\"price\":1000,\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trade.version").value(2))
                .andExpect(jsonPath("$.holding.cash_dividends").value(4500));
        // 另一台裝置還拿著 version 1
        mvc.perform(put("/api/v2/trades/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"side\":\"BUY\",\"trade_date\":\"2025-06-11\",\"shares\":5,\"price\":1000,\"version\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        mvc.perform(delete("/api/v2/trades/" + id).param("version", "1"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        // 沒帶 version
        mvc.perform(put("/api/v2/trades/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"side\":\"BUY\",\"trade_date\":\"2025-06-11\",\"shares\":5,\"price\":1000}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v2/trades/" + id).param("version", "2")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v2/holdings/" + hid)).andExpect(status().isNotFound());
    }

    @Test
    void deletingABuyThatALaterSellNeedsIsRejected() throws Exception {
        JsonNode b1 = json(postTrade("a", buy("2025-01-02", 1000, "1000")));
        postTrade("b", sell("2025-03-01", 1000, "1100")).andExpect(status().isCreated());
        long id = b1.path("trade").path("id").asLong();
        mvc.perform(delete("/api/v2/trades/" + id).param("version", "1"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INSUFFICIENT_SHARES"));
        assertThat(trades.findAll()).hasSize(2);
    }

    @Test
    void parallelSellsFromTwoDevicesCannotBothPass() throws Exception {
        postTrade("a", buy("2025-01-02", 1000, "1000"));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String k = "s" + i;
            results.add(pool.submit(() -> postTrade(k, sell("2025-03-01", 600, "1100")).andReturn().getResponse().getStatus()));
        }
        int created = 0;
        for (Future<Integer> f : results) {
            created += f.get() == 201 ? 1 : 0;
        }
        pool.shutdown();
        assertThat(created).isEqualTo(1);
    }

    @Test
    void validation() throws Exception {
        String[] bad = {
                "{\"code\":\"2330\",\"side\":\"HOLD\",\"trade_date\":\"2025-06-10\",\"shares\":1,\"price\":1}",
                "{\"code\":\"2330\",\"side\":\"BUY\",\"trade_date\":\"2025/06/10\",\"shares\":1,\"price\":1}",
                "{\"code\":\"2330\",\"side\":\"BUY\",\"trade_date\":\"2099-01-01\",\"shares\":1,\"price\":1}",
                "{\"code\":\"2330\",\"side\":\"BUY\",\"trade_date\":\"2002-01-01\",\"shares\":1,\"price\":1}",
                "{\"code\":\"2330\",\"side\":\"BUY\",\"trade_date\":\"2025-06-10\",\"shares\":0,\"price\":1}",
                "{\"code\":\"2330\",\"side\":\"BUY\",\"trade_date\":\"2025-06-10\",\"shares\":1.5,\"price\":1}",
                "{\"code\":\"2330\",\"side\":\"BUY\",\"trade_date\":\"2025-06-10\",\"shares\":\"300\",\"price\":1}",
                "{\"code\":\"2330\",\"side\":\"BUY\",\"trade_date\":\"2025-06-10\",\"shares\":300,\"price\":1.005}",
                "{\"code\":\"2330\",\"side\":\"BUY\",\"shares\":300,\"price\":1}",
        };
        for (String b : bad) {
            postTrade(null, b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        assertThat(trades.findAll()).isEmpty();
    }

    @Test
    void summaryV2() throws Exception {
        postTrade("a", buy("2025-06-10", 1000, "1000"));
        postTrade("b", sell("2025-07-01", 400, "1100"));
        JsonNode s = json(mvc.perform(get("/api/v2/summary")).andExpect(status().isOk()));
        assertThat(s.path("cash_dividends").asLong()).isEqualTo(4500);
        assertThat(s.path("total_return").asLong()).isEqualTo(
                s.path("realized_pnl").asLong() + s.path("unrealized_pnl").asLong() + 4500);
        assertThat(s.path("price_missing").asInt()).isZero();
    }

    // ---- App 1.0 連到 2.0 伺服器 ----

    @Test
    void app10SeesOddLotsAsWholeLotsButPnlOnRealShares() throws Exception {
        postTrade("a", buy("2026-11-02", 1000, "1000"));
        postTrade("b", buy("2026-11-03", 300, "1000"));
        mvc.perform(get("/api/v1/holdings"))
                .andExpect(jsonPath("$[0].lots").value(1))
                .andExpect(jsonPath("$[0].buy_price").value(1000.00));
        // 1,300 股：賣 1,430,000 − 2,037 − 4,290 − 成本 1,301,852 ＝ 121,821
        mvc.perform(get("/api/v1/holdings")).andExpect(jsonPath("$[0].pnl").value(121821));
    }

    @Test
    void app10CannotEditMultiTradeHoldingButCanDelete() throws Exception {
        JsonNode r = json(postTrade("a", buy("2026-11-02", 1000, "1000")));
        long hid = r.path("trade").path("holding_id").asLong();
        postTrade("b", buy("2026-11-03", 1000, "1000"));
        mvc.perform(put("/api/v1/holdings/" + hid).contentType(MediaType.APPLICATION_JSON).content("{\"lots\":5,\"buy_price\":900}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message", containsString("2.0")));
        mvc.perform(delete("/api/v1/holdings/" + hid)).andExpect(status().isNoContent());
        assertThat(trades.findAll()).isEmpty();
    }

    @Test
    void app10DoesNotListFullySoldAndCanBuyAgain() throws Exception {
        postTrade("a", buy("2025-01-02", 1000, "1000"));
        postTrade("b", sell("2025-03-01", 1000, "1100"));
        mvc.perform(get("/api/v1/holdings")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/summary")).andExpect(jsonPath("$.total_cost").value(0));
        mvc.perform(post("/api/v1/holdings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.lots").value(1));
        // 還持有中時重複新增照 1.0 規格回 409
        mvc.perform(post("/api/v1/holdings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000}"))
                .andExpect(status().isConflict());
        assertThat(trades.findAll()).hasSize(3);
    }

    @Test
    void notFound() throws Exception {
        mvc.perform(get("/api/v2/holdings/999")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v2/trades/999")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v2/trades/999").param("version", "1")).andExpect(status().isNotFound());
        when(quotes.lookup(anyString())).thenReturn(new QuoteService.Lookup.NotFound());
        postTrade(null, buy("2025-06-10", 1, "1").replace("2330", "9999"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("STOCK_NOT_FOUND"));
    }
}
