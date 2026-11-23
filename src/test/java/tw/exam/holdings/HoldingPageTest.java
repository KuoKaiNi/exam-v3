package tw.exam.holdings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tw.exam.holdings.holding.HoldingService;
import tw.exam.holdings.holding.TradeRepository;
import tw.exam.holdings.quote.Market;
import tw.exam.holdings.quote.Quote;
import tw.exam.holdings.quote.QuoteService;

/** 網頁：記交易、修改、刪除、重複送出、賣超。行情用假的，不連官方。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HoldingPageTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    HoldingService holdings;

    @Autowired
    TradeRepository trades;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    QuoteService quotes;

    final Quote q = new Quote("2330", "台積電", Market.TWSE, LocalDate.of(2026, 11, 23), new BigDecimal("1100.00"));

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM holding").update();
        jdbc.sql("DELETE FROM idempotency_key").update();
        when(quotes.lookup("2330")).thenReturn(new QuoteService.Lookup.Found(Market.TWSE, "台積電", q));
    }

    @Test
    void recordBuysAndSellAndShowTotals() throws Exception {
        mvc.perform(post("/trades").param("code", "2330").param("side", "BUY").param("tradeDate", "2026-10-01")
                        .param("shares", "1000").param("price", "1000").param("requestId", "r1"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/holdings"));
        long id = holdings.findByCode("2330").orElseThrow().id();
        mvc.perform(post("/trades").param("code", "2330").param("side", "BUY").param("tradeDate", "2026-10-02")
                        .param("shares", "300").param("price", "1000").param("requestId", "r2").param("back", String.valueOf(id)))
                .andExpect(redirectedUrl("/holdings/" + id));
        when(quotes.cached("2330")).thenReturn(Optional.of(q));
        // 1,300 股；成本 1,000,000＋1,425＋300,000＋427＝1,301,852；平均 1,001.42
        mvc.perform(get("/holdings"))
                .andExpect(content().string(containsString("1,300")))
                .andExpect(content().string(containsString("1,001.42")))
                .andExpect(content().string(containsString("含息總報酬")));
        mvc.perform(get("/holdings/" + id))
                .andExpect(content().string(containsString("交易紀錄")))
                // 買進那幾列沒有已實現損益，不能顯示「未取得」
                .andExpect(content().string(not(containsString("未取得"))))
                .andExpect(content().string(containsString("2026-10-02")));
    }

    @Test
    void doubleSubmitRecordsOnce() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/trades").param("code", "2330").param("side", "BUY").param("tradeDate", "2026-10-01")
                    .param("shares", "1000").param("price", "1000").param("requestId", "same"))
                    .andExpect(status().is3xxRedirection());
        }
        assertThat(trades.findAll()).hasSize(1);
    }

    @Test
    void oversellShowsErrorAndIsNotSaved() throws Exception {
        mvc.perform(post("/trades").param("code", "2330").param("side", "BUY").param("tradeDate", "2026-10-01")
                .param("shares", "1000").param("price", "1000").param("requestId", "a"));
        mvc.perform(post("/trades").param("code", "2330").param("side", "SELL").param("tradeDate", "2026-10-05")
                        .param("shares", "1500").param("price", "1100").param("requestId", "b"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("當時只持有 1000 股")));
        assertThat(trades.findAll()).hasSize(1);
    }

    @Test
    void validationErrorsKeepInput() throws Exception {
        mvc.perform(post("/trades").param("code", "2330").param("side", "BUY").param("tradeDate", "2099-01-01")
                        .param("shares", "1.5").param("price", "abc").param("requestId", "x"))
                .andExpect(content().string(containsString("交易日期不能晚於今天")))
                .andExpect(content().string(containsString("股數要填整數")))
                .andExpect(content().string(containsString("價格要填數字")));
        assertThat(trades.findAll()).isEmpty();
    }

    @Test
    void editAndDeleteWithVersion() throws Exception {
        mvc.perform(post("/trades").param("code", "2330").param("side", "BUY").param("tradeDate", "2026-10-01")
                .param("shares", "1000").param("price", "1000").param("requestId", "a"));
        long tid = trades.findAll().get(0).id();
        mvc.perform(post("/trades/" + tid).param("side", "BUY").param("tradeDate", "2026-09-01")
                        .param("shares", "2000").param("price", "990").param("version", "1").param("requestId", "e1"))
                .andExpect(status().is3xxRedirection());
        assertThat(trades.findById(tid).orElseThrow().shares()).isEqualTo(2000);
        // 用舊版本再改一次：被擋
        mvc.perform(post("/trades/" + tid).param("side", "BUY").param("tradeDate", "2026-09-01")
                        .param("shares", "3000").param("price", "990").param("version", "1").param("requestId", "e2"))
                .andExpect(content().string(containsString("已經在別的裝置上改過")));
        mvc.perform(post("/trades/" + tid + "/delete").param("version", "2")).andExpect(redirectedUrl("/holdings"));
        assertThat(trades.findAll()).isEmpty();
        assertThat(holdings.list()).isEmpty();
        mvc.perform(get("/holdings")).andExpect(content().string(not(containsString("台積電"))));
    }

    @Test
    void unknownCodeAndOfficialDownAreNotSaved() throws Exception {
        when(quotes.lookup(anyString())).thenReturn(new QuoteService.Lookup.NotFound());
        mvc.perform(post("/trades").param("code", "9999").param("side", "BUY").param("tradeDate", "2026-10-01")
                        .param("shares", "1").param("price", "10").param("requestId", "n1"))
                .andExpect(content().string(containsString("查不到股票代號 9999")));
        when(quotes.lookup(anyString())).thenReturn(new QuoteService.Lookup.Unavailable("down"));
        mvc.perform(post("/trades").param("code", "2317").param("side", "BUY").param("tradeDate", "2026-10-01")
                        .param("shares", "1").param("price", "10").param("requestId", "n2"))
                .andExpect(content().string(containsString("官方行情網站暫時查不到")));
        assertThat(holdings.list()).isEmpty();
    }
}
