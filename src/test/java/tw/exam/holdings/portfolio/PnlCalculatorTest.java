package tw.exam.holdings.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import tw.exam.holdings.holding.Holding;
import tw.exam.holdings.ledger.Ledger;
import tw.exam.holdings.ledger.Side;
import tw.exam.holdings.ledger.Trade;
import tw.exam.holdings.quote.Market;
import tw.exam.holdings.quote.Quote;

/** 純計算，不連任何網站。 */
class PnlCalculatorTest {

    @ParameterizedTest(name = "{0}：{1} {2} 張 買 {3} 收 {4} → 盈虧 {5}、收益率 {6}%")
    @CsvSource({
            // 題目給的三個例子
            "一般股票,        2330, 1, 1000.00, 1100.00,  93708,  9.36",
            "ETF,             0050, 2,  150.00,  145.50, -10132, -3.37",
            "手續費低於 20 元, 2363, 1,   10.00,   10.50,    429,  4.28",
    })
    void examplesFromTheExam(String label, String code, int lots, String buy, String close, long pnl, String pct) {
        PnlCalculator.Pnl r = PnlCalculator.calculate(code, lots, new BigDecimal(buy), new BigDecimal(close));
        assertThat(r.pnl()).isEqualTo(pnl);
        assertThat(r.returnPct()).isEqualByComparingTo(pct);
    }

    @Test
    void stockBreakdown() {
        // 買 1,000,000，手續費 1,425；賣 1,100,000，手續費 1,567.5 → 1,567，證交稅 3,300
        PnlCalculator.Pnl r = PnlCalculator.calculate("2330", 1, new BigDecimal("1000"), new BigDecimal("1100"));
        assertThat(r.buyAmount()).isEqualTo(1_000_000);
        assertThat(r.buyFee()).isEqualTo(1_425);
        assertThat(r.sellAmount()).isEqualTo(1_100_000);
        assertThat(r.sellFee()).isEqualTo(1_567);
        assertThat(r.tax()).isEqualTo(3_300);
    }

    @Test
    void etfBreakdown() {
        // 買 300,000，手續費 427.5 → 427；賣 291,000，手續費 414.675 → 414，ETF 證交稅 0.1% = 291
        PnlCalculator.Pnl r = PnlCalculator.calculate("0050", 2, new BigDecimal("150"), new BigDecimal("145.5"));
        assertThat(r.buyFee()).isEqualTo(427);
        assertThat(r.sellFee()).isEqualTo(414);
        assertThat(r.tax()).isEqualTo(291);
    }

    @Test
    void minimumFeeAndTaxFloor() {
        // 買 10,000 手續費 14.25 → 最低 20；賣 10,500 手續費 14.96 → 20；證交稅 31.5 → 31
        PnlCalculator.Pnl r = PnlCalculator.calculate("2363", 1, new BigDecimal("10"), new BigDecimal("10.5"));
        assertThat(r.buyFee()).isEqualTo(20);
        assertThat(r.sellFee()).isEqualTo(20);
        assertThat(r.tax()).isEqualTo(31);
    }

    @Test
    void feeExactlyAtMinimumBoundary() {
        // 14,035 × 0.1425% = 19.99 → 19 → 最低 20；14,036 × 0.1425% = 20.0013 → 20
        assertThat(PnlCalculator.fee(14_035)).isEqualTo(20);
        assertThat(PnlCalculator.fee(14_036)).isEqualTo(20);
        assertThat(PnlCalculator.fee(15_000)).isEqualTo(21);
    }

    @Test
    void etfIsOnlyCodesStartingWith00() {
        assertThat(PnlCalculator.isEtf("0050")).isTrue();
        assertThat(PnlCalculator.isEtf("00631L")).isTrue();
        assertThat(PnlCalculator.isEtf("006208")).isTrue();
        assertThat(PnlCalculator.isEtf("2330")).isFalse();
        assertThat(PnlCalculator.isEtf("0101")).isFalse();
    }

    @Test
    void returnPctRoundsHalfUpAwayFromZero() {
        assertThat(PnlCalculator.returnPct(3375, 100_000)).isEqualByComparingTo("3.38");
        assertThat(PnlCalculator.returnPct(-3375, 100_000)).isEqualByComparingTo("-3.38");
        assertThat(PnlCalculator.returnPct(-3374, 100_000)).isEqualByComparingTo("-3.37");
        assertThat(PnlCalculator.returnPct(0, 0)).isEqualByComparingTo("0");
    }

    /** 1.0 的一筆持股＝一筆買進；summary 照 1.0 規格算。 */
    static Position position(long id, String code, int lots, String buy, String close) {
        Holding h = new Holding(id, code, code, Market.TWSE);
        List<Trade> trades = List.of(new Trade(id, id, Side.BUY, LocalDate.of(2026, 11, 1), lots * 1000L, new BigDecimal(buy), 1));
        Quote q = close == null ? null
                : new Quote(code, code, Market.TWSE, LocalDate.of(2026, 11, 12), new BigDecimal(close));
        return new Position(h, trades, Ledger.compute(code, trades, List.of(), LocalDate.of(2026, 11, 23)), q, true);
    }

    @Test
    void summaryMatchesApiSpecExample() {
        // App 1.0 API 規格的總計範例：2330 與 0050 兩筆
        Summary s = Summary.of(List.of(
                position(1, "2330", 1, "1000.00", "1100.00"),
                position(2, "0050", 2, "150.00", "145.50")));
        assertThat(s.totalCost()).isEqualTo(1_301_852);
        assertThat(s.marketValue()).isEqualTo(1_391_000);
        assertThat(s.pnl()).isEqualTo(83_576);
        assertThat(s.returnPct()).isEqualByComparingTo("6.42");
        assertThat(s.quoteMissing()).isZero();
    }

    @Test
    void summaryWithMissingQuoteCountsCostOnly() {
        Summary s = Summary.of(List.of(
                position(1, "2330", 1, "1000.00", "1100.00"),
                position(2, "2363", 1, "10.00", null)));
        assertThat(s.totalCost()).isEqualTo(1_001_425 + 10_020);
        assertThat(s.marketValue()).isEqualTo(1_100_000);
        assertThat(s.pnl()).isEqualTo(93_708);
        assertThat(s.quoteMissing()).isEqualTo(1);
        // 93,708 ÷ 1,011,445 = 9.2648…%
        assertThat(s.returnPct()).isEqualByComparingTo("9.26");
    }

    @Test
    void emptySummary() {
        Summary s = Summary.of(List.of());
        assertThat(s.totalCost()).isZero();
        assertThat(s.pnl()).isZero();
        assertThat(s.returnPct()).isEqualByComparingTo("0");
    }
}
