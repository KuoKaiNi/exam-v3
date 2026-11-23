package tw.exam.holdings;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

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

import tw.exam.holdings.quote.Market;
import tw.exam.holdings.quote.Quote;
import tw.exam.holdings.quote.QuoteService;

/** 照《App 1.0 API 規格》逐項驗：欄位名稱、型別、HTTP 狀態碼、錯誤格式。行情用假的，不連官方。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HoldingApiTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    QuoteService quotes;

    final Quote q2330 = new Quote("2330", "台積電", Market.TWSE, LocalDate.of(2026, 11, 12), new BigDecimal("1100.00"));
    final Quote q0050 = new Quote("0050", "元大台灣50", Market.TWSE, LocalDate.of(2026, 11, 12), new BigDecimal("145.50"));
    final Quote q6488 = new Quote("6488", "環球晶", Market.TPEX, LocalDate.of(2026, 11, 12), new BigDecimal("460.50"));

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM holding").update();
        for (Quote q : new Quote[] {q2330, q0050, q6488}) {
            when(quotes.lookup(q.code())).thenReturn(new QuoteService.Lookup.Found(q.market(), q.name(), q));
        }
        when(quotes.lookup("9999")).thenReturn(new QuoteService.Lookup.NotFound());
        when(quotes.cached("2330")).thenReturn(Optional.of(q2330));
        when(quotes.cached("0050")).thenReturn(Optional.of(q0050));
    }

    ResultActions postJson(String body) throws Exception {
        return mvc.perform(post("/api/v1/holdings").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions putJson(long id, String body) throws Exception {
        return mvc.perform(put("/api/v1/holdings/" + id).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    long create(String body) throws Exception {
        String json = postJson(body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(json).get("id").asLong();
    }

    ResultActions expectError(ResultActions r, int status, String code) throws Exception {
        return r.andExpect(status().is(status))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message").isString());
    }

    @Test
    void createReturnsSpecExampleObject() throws Exception {
        String body = postJson("{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode h = JSON.readTree(body);
        // 規格範例：id 12 以外全部一樣
        org.assertj.core.api.Assertions.assertThat(h.get("id").isIntegralNumber()).isTrue();
        org.assertj.core.api.Assertions.assertThat(body).contains(
                "\"code\":\"2330\",\"name\":\"台積電\",\"market\":\"TWSE\",\"lots\":1,\"buy_price\":1000.00,"
                        + "\"current_price\":1100.00,\"price_date\":\"2026-11-12\",\"pnl\":93708,\"return_pct\":9.36");
    }

    @Test
    void fieldTypesMatchSpecIncludingNulls() throws Exception {
        create("{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000}");
        create("{\"code\":\"6488\",\"lots\":3,\"buy_price\":500.5}");
        // 6488 沒有快取收盤價：四個欄位要是 null，不能省略
        String body = mvc.perform(get("/api/v1/holdings")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        JsonNode list = JSON.readTree(body);
        org.assertj.core.api.Assertions.assertThat(list.isArray()).isTrue();
        for (JsonNode h : list) {
            org.assertj.core.api.Assertions.assertThat(h.get("id").isIntegralNumber()).isTrue();
            org.assertj.core.api.Assertions.assertThat(h.get("code").isTextual()).isTrue();
            org.assertj.core.api.Assertions.assertThat(h.get("name").isTextual()).isTrue();
            org.assertj.core.api.Assertions.assertThat(h.get("market").asText()).isIn("TWSE", "TPEX");
            org.assertj.core.api.Assertions.assertThat(h.get("lots").isIntegralNumber()).isTrue();
            org.assertj.core.api.Assertions.assertThat(h.get("buy_price").isNumber()).isTrue();
            for (String f : new String[] {"current_price", "price_date", "pnl", "return_pct"}) {
                org.assertj.core.api.Assertions.assertThat(h.has(f)).as(f).isTrue();
            }
        }
        JsonNode otc = list.get(1);
        org.assertj.core.api.Assertions.assertThat(otc.get("market").asText()).isEqualTo("TPEX");
        org.assertj.core.api.Assertions.assertThat(otc.get("buy_price").decimalValue()).isEqualByComparingTo("500.50");
        org.assertj.core.api.Assertions.assertThat(otc.get("current_price").isNull()).isTrue();
        org.assertj.core.api.Assertions.assertThat(otc.get("price_date").isNull()).isTrue();
        org.assertj.core.api.Assertions.assertThat(otc.get("pnl").isNull()).isTrue();
        org.assertj.core.api.Assertions.assertThat(otc.get("return_pct").isNull()).isTrue();
    }

    @Test
    void emptyListIsEmptyArray() throws Exception {
        mvc.perform(get("/api/v1/holdings")).andExpect(status().isOk()).andExpect(content().json("[]", true));
    }

    @Test
    void summaryMatchesSpecExample() throws Exception {
        create("{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000}");
        create("{\"code\":\"0050\",\"lots\":2,\"buy_price\":150.00}");
        mvc.perform(get("/api/v1/summary")).andExpect(status().isOk())
                .andExpect(content().json("{\"total_cost\":1301852,\"market_value\":1391000,\"pnl\":83576,"
                        + "\"return_pct\":6.42,\"quote_missing\":0}", true));
    }

    @Test
    void summaryCountsMissingQuotes() throws Exception {
        create("{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000}");
        create("{\"code\":\"6488\",\"lots\":1,\"buy_price\":400}");
        mvc.perform(get("/api/v1/summary"))
                .andExpect(jsonPath("$.quote_missing").value(1))
                .andExpect(jsonPath("$.total_cost").value(1001425 + 400570))
                .andExpect(jsonPath("$.market_value").value(1100000))
                .andExpect(jsonPath("$.pnl").value(93708));
    }

    @Test
    void emptySummary() throws Exception {
        mvc.perform(get("/api/v1/summary"))
                .andExpect(content().json("{\"total_cost\":0,\"market_value\":0,\"pnl\":0,\"return_pct\":0,\"quote_missing\":0}"));
    }

    @Test
    void getOneAndNotFound() throws Exception {
        long id = create("{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000}");
        mvc.perform(get("/api/v1/holdings/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        expectError(mvc.perform(get("/api/v1/holdings/" + (id + 1000))), 404, "NOT_FOUND");
        expectError(mvc.perform(get("/api/v1/holdings/abc")), 404, "NOT_FOUND");
    }

    @Test
    void duplicateIs409AndNotSaved() throws Exception {
        create("{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000}");
        expectError(postJson("{\"code\":\"2330\",\"lots\":5,\"buy_price\":900}"), 409, "DUPLICATE_CODE")
                .andExpect(jsonPath("$.error.message").value("2330 已在持股中"));
        mvc.perform(get("/api/v1/holdings")).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].lots").value(1));
    }

    @Test
    void unknownCodeIs404StockNotFound() throws Exception {
        expectError(postJson("{\"code\":\"9999\",\"lots\":1,\"buy_price\":10}"), 404, "STOCK_NOT_FOUND");
        mvc.perform(get("/api/v1/holdings")).andExpect(content().json("[]"));
    }

    @Test
    void officialDownIs503AndNotSaved() throws Exception {
        when(quotes.lookup(anyString())).thenReturn(new QuoteService.Lookup.Unavailable("down"));
        expectError(postJson("{\"code\":\"2317\",\"lots\":1,\"buy_price\":10}"), 503, "QUOTE_UNAVAILABLE");
        mvc.perform(get("/api/v1/holdings")).andExpect(content().json("[]"));
    }

    @Test
    void validationErrorsForWhatApp10ActuallySends() throws Exception {
        // App 1.0：張數填 1.5、abc 會原樣送字串；空白送 null；不會先在手機上檢查
        String[] bad = {
                "{\"code\":\"2330\",\"lots\":\"1.5\",\"buy_price\":1000}",
                "{\"code\":\"2330\",\"lots\":1.5,\"buy_price\":1000}",
                "{\"code\":\"2330\",\"lots\":null,\"buy_price\":1000}",
                "{\"code\":\"2330\",\"buy_price\":1000}",
                "{\"code\":\"2330\",\"lots\":0,\"buy_price\":1000}",
                "{\"code\":\"2330\",\"lots\":1000,\"buy_price\":1000}",
                "{\"code\":\"2330\",\"lots\":99999999999,\"buy_price\":1000}",
                "{\"code\":\"2330\",\"lots\":1,\"buy_price\":\"abc\"}",
                "{\"code\":\"2330\",\"lots\":1,\"buy_price\":0}",
                "{\"code\":\"2330\",\"lots\":1,\"buy_price\":-5}",
                "{\"code\":\"2330\",\"lots\":1,\"buy_price\":10.555}",
                "{\"code\":\"2330\",\"lots\":1,\"buy_price\":null}",
                "{\"code\":\"\",\"lots\":1,\"buy_price\":10}",
                "{\"code\":null,\"lots\":1,\"buy_price\":10}",
                "{\"code\":2330,\"lots\":1,\"buy_price\":10}",
                "{\"lots\":1,\"buy_price\":10}",
                "not json",
                "[1,2]",
                "",
        };
        for (String body : bad) {
            expectError(postJson(body), 400, "VALIDATION_ERROR");
        }
        mvc.perform(get("/api/v1/holdings")).andExpect(content().json("[]"));
    }

    @Test
    void updateAndValidation() throws Exception {
        long id = create("{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000}");
        putJson(id, "{\"lots\":2,\"buy_price\":980.5}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lots").value(2))
                .andExpect(jsonPath("$.buy_price").value(980.5))
                .andExpect(jsonPath("$.code").value("2330"));
        // 代號不能改：送了 code 也不理
        putJson(id, "{\"code\":\"0050\",\"lots\":3,\"buy_price\":980.5}").andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("2330"));
        expectError(putJson(id, "{\"lots\":\"abc\",\"buy_price\":980.5}"), 400, "VALIDATION_ERROR")
                .andExpect(jsonPath("$.error.message", containsString("張數")));
        expectError(putJson(id + 1000, "{\"lots\":2,\"buy_price\":980.5}"), 404, "NOT_FOUND");
        mvc.perform(get("/api/v1/holdings/" + id)).andExpect(jsonPath("$.lots").value(3));
    }

    @Test
    void deleteIs204ThenNotFound() throws Exception {
        long id = create("{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000}");
        mvc.perform(delete("/api/v1/holdings/" + id)).andExpect(status().isNoContent())
                .andExpect(content().string(""));
        expectError(mvc.perform(delete("/api/v1/holdings/" + id)), 404, "NOT_FOUND");
    }

    @Test
    void unknownApiPathsAlsoUseErrorFormat() throws Exception {
        expectError(mvc.perform(get("/api/v1/nothing")), 404, "NOT_FOUND");
        expectError(mvc.perform(post("/api/v1/summary")), 405, "METHOD_NOT_ALLOWED");
    }
}
