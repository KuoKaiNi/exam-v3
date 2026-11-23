package tw.exam.holdings.dividend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import tw.exam.holdings.ledger.ExRight;

/** 用 2026-11-23 實際從官方抓下來的回應（只留幾筆）來測，不連網路。 */
class ExRightParserTest {

    static String fixture(String name) throws IOException {
        try (InputStream in = ExRightParserTest.class.getResourceAsStream("/official/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void twseList() throws Exception {
        ExRightParser.Parsed p = ExRightParser.parseTwse(fixture("twse_twt49u_202506_202507.json"));
        assertThat(p.ok()).isTrue();
        ExRight tsmc = p.rows().stream().filter(r -> r.code().equals("2330")).findFirst().orElseThrow();
        assertThat(tsmc.exDate()).isEqualTo(LocalDate.of(2025, 6, 12));
        assertThat(tsmc.kind()).isEqualTo("息");
        // 除息：權值+息值 4.500020 就是每股現金股利
        assertThat(tsmc.cashPerShare()).isEqualByComparingTo("4.50002");
        assertThat(tsmc.stockPerThousand()).isEqualByComparingTo("0");
        // 權息：只有合計值 1.527222，不能當現金股利用，要查明細
        ExRight esun = p.rows().stream().filter(r -> r.code().equals("2884")).findFirst().orElseThrow();
        assertThat(esun.kind()).isEqualTo("權息");
        assertThat(esun.known()).isFalse();
    }

    @Test
    void twseDetail() throws Exception {
        BigDecimal[] d = ExRightParser.parseTwseDetail(fixture("twse_detail_2884_20250722.json")).orElseThrow();
        assertThat(d[0]).isEqualByComparingTo("1.2");
        assertThat(d[1]).isEqualByComparingTo("10");
        BigDecimal[] t = ExRightParser.parseTwseDetail(fixture("twse_detail_2330_20250612.json")).orElseThrow();
        assertThat(t[0]).isEqualByComparingTo("4.50002");
        assertThat(ExRightParser.parseTwseDetail(fixture("twse_detail_notfound.json"))).isEmpty();
    }

    @Test
    void tpexList() throws Exception {
        ExRightParser.Parsed p = ExRightParser.parseTpex(fixture("tpex_exdailyq_202506_202507.json"));
        assertThat(p.ok()).isTrue();
        ExRight eink = p.rows().stream().filter(r -> r.code().equals("8069")).findFirst().orElseThrow();
        assertThat(eink.exDate()).isEqualTo(LocalDate.of(2025, 7, 24));
        assertThat(eink.kind()).isEqualTo("除息");
        assertThat(eink.cashPerShare()).isEqualByComparingTo("4.99013930");
        assertThat(eink.stockPerThousand()).isEqualByComparingTo("0");
        ExRight both = p.rows().stream().filter(r -> r.code().equals("6762")).findFirst().orElseThrow();
        assertThat(both.stockPerThousand()).isEqualByComparingTo("150.00000327");
    }

    @Test
    void garbage() {
        assertThat(ExRightParser.parseTwse("<html>").ok()).isFalse();
        assertThat(ExRightParser.parseTpex("").ok()).isFalse();
        assertThat(ExRightParser.parseTwse("{\"stat\":\"很抱歉，沒有符合條件的資料!\"}").rows()).isEmpty();
    }
}
