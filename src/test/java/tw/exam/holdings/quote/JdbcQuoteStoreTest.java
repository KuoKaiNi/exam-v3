package tw.exam.holdings.quote;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
class JdbcQuoteStoreTest {

    @Autowired
    JdbcQuoteStore store;

    @Test
    void savedQuotesComeBackOnlyForTheSameHost() {
        Quote q1 = new Quote("2330", "台積電", Market.TWSE, LocalDate.of(2026, 11, 20), new BigDecimal("2050.00"));
        Quote q2 = new Quote("2330", "台積電", Market.TWSE, LocalDate.of(2026, 11, 23), new BigDecimal("2030.00"));
        Quote q3 = new Quote("6488", "環球晶", Market.TPEX, LocalDate.of(2026, 11, 23), new BigDecimal("460.50"));
        Instant at = Instant.parse("2026-11-23T07:00:00Z");
        store.save(q1, "https://www.twse.com.tw", at);
        store.save(q2, "https://www.twse.com.tw", at);
        store.save(q3, "https://www.tpex.org.tw", at);

        assertThat(store.load("https://www.twse.com.tw", "https://www.tpex.org.tw"))
                .extracting(QuoteStore.Saved::quote)
                .containsExactlyInAnyOrder(q2, q3);
        assertThat(store.load("https://www.twse.com.tw", "https://www.tpex.org.tw").get(0).fetchedAt()).isEqualTo(at);

        // 換成測試伺服器：舊主機查到的資料不沿用
        assertThat(store.load("http://localhost:9000", "http://localhost:9000")).isEmpty();
        assertThat(store.load("http://localhost:9000", "https://www.tpex.org.tw"))
                .extracting(QuoteStore.Saved::quote).containsExactly(q3);

        store.delete("2330");
        assertThat(store.load("https://www.twse.com.tw", "https://www.tpex.org.tw"))
                .extracting(QuoteStore.Saved::quote).containsExactly(q3);
    }
}
