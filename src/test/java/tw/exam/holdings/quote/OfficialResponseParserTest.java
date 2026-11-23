package tw.exam.holdings.quote;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** 用 2026-11-23 晚上實際從官方抓下來的回應存成檔案來測，不連網路。 */
class OfficialResponseParserTest {

    static String fixture(String name) throws IOException {
        try (InputStream in = OfficialResponseParserTest.class.getResourceAsStream("/official/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void twseStockDay() throws Exception {
        MonthData d = OfficialResponseParser.parseTwse(fixture("twse_2330_202611.json"), "2330");
        assertThat(d.status()).isEqualTo(MonthData.Status.FOUND);
        assertThat(d.name()).isEqualTo("台積電");
        assertThat(d.days()).hasSize(16);
        assertThat(d.latest()).get().satisfies(c -> {
            assertThat(c.date()).isEqualTo(LocalDate.of(2026, 11, 23));
            assertThat(c.close()).isEqualByComparingTo("2030.00");
        });
    }

    @Test
    void tpexTradingStock() throws Exception {
        MonthData d = OfficialResponseParser.parseTpex(fixture("tpex_6488_202611.json"), "6488");
        assertThat(d.status()).isEqualTo(MonthData.Status.FOUND);
        assertThat(d.name()).isEqualTo("環球晶");
        assertThat(d.latest()).get().satisfies(c -> {
            assertThat(c.date()).isEqualTo(LocalDate.of(2026, 11, 23));
            assertThat(c.close()).isEqualByComparingTo("460.50");
        });
    }

    @Test
    void notFoundIsEmptyNotFailure() throws Exception {
        assertThat(OfficialResponseParser.parseTwse(fixture("twse_notfound.json"), "9999").status())
                .isEqualTo(MonthData.Status.EMPTY);
        assertThat(OfficialResponseParser.parseTpex(fixture("tpex_notfound.json"), "9999").status())
                .isEqualTo(MonthData.Status.EMPTY);
    }

    @Test
    void garbageIsFailure() {
        assertThat(OfficialResponseParser.parseTwse("<html>blocked</html>", "2330").status())
                .isEqualTo(MonthData.Status.FAILED);
        assertThat(OfficialResponseParser.parseTpex("", "6488").status())
                .isEqualTo(MonthData.Status.FAILED);
    }

    @Test
    void daysWithoutTradeAreSkipped() {
        String body = """
                {"stat":"OK","title":"115年11月 1234 測試股 各日成交資訊",
                 "fields":["日期","成交股數","成交金額","開盤價","最高價","最低價","收盤價","漲跌價差","成交筆數","註記"],
                 "data":[["115/11/20","1","1","10","10","10","10.50","0","1",""],
                         ["115/11/23","0","0","--","--","--","--","0","0",""]]}
                """;
        MonthData d = OfficialResponseParser.parseTwse(body, "1234");
        assertThat(d.name()).isEqualTo("測試股");
        assertThat(d.latest()).get().satisfies(c -> {
            assertThat(c.date()).isEqualTo(LocalDate.of(2026, 11, 20));
            assertThat(c.close()).isEqualByComparingTo("10.50");
        });
    }

    @Test
    void rocDate() {
        assertThat(OfficialResponseParser.rocDate("115/06/05")).isEqualTo(LocalDate.of(2026, 6, 5));
        assertThat(OfficialResponseParser.rocDate("99/01/04")).isEqualTo(LocalDate.of(2010, 1, 4));
    }
}
