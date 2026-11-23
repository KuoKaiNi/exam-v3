package tw.exam.holdings.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 2.0 的計算規則。股利數字是 2025 年官方實際資料（證交所 TWT49U／TWT49UDetail、櫃買中心 exDailyQ）。 */
class LedgerTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 23);
    // 2330 台積電 2025-06-12 除息，每股 4.50002 元（TWT49U 權值+息值 4.500020，明細 4.50002 元／股）
    static final ExRight TSMC_2025Q = new ExRight("2330", LocalDate.of(2025, 6, 12), "息", new BigDecimal("4.50002"), BigDecimal.ZERO);
    // 2884 玉山金 2025-07-22 除權息：現金 1.2 元、每千股配 10 股（TWT49UDetail）
    static final ExRight ESUN_2025 = new ExRight("2884", LocalDate.of(2025, 7, 22), "權息", new BigDecimal("1.2"), new BigDecimal("10"));
    // 8069 元太 2025-07-24 除息，現金股利 4.99013930（exDailyQ）
    static final ExRight EINK_2025 = new ExRight("8069", LocalDate.of(2025, 7, 24), "除息", new BigDecimal("4.99013930"), new BigDecimal("0.00000000"));

    long nextId = 1;

    Trade buy(String date, long shares, String price) {
        return new Trade(nextId++, 1, Side.BUY, LocalDate.parse(date), shares, new BigDecimal(price), 1);
    }

    Trade sell(String date, long shares, String price) {
        return new Trade(nextId++, 1, Side.SELL, LocalDate.parse(date), shares, new BigDecimal(price), 1);
    }

    // ---- 題目的四個例子 ----

    @Test
    void cashDividendOnly_2330() {
        LedgerResult r = Ledger.compute("2330", List.of(buy("2025-06-10", 1500, "1000")), List.of(TSMC_2025Q), TODAY);
        assertThat(r.cashDividends()).isEqualTo(6_750);
        assertThat(r.stockDividendShares()).isZero();
        assertThat(r.shares()).isEqualTo(1500);
    }

    @Test
    void cashAndStock_2884() {
        LedgerResult r = Ledger.compute("2884", List.of(buy("2025-07-01", 2000, "30.00")), List.of(ESUN_2025), TODAY);
        assertThat(r.cashDividends()).isEqualTo(2_400);
        assertThat(r.stockDividendShares()).isEqualTo(20);
        assertThat(r.shares()).isEqualTo(2_020);
        // 成本 60,000 ＋ 手續費 85 ＝ 60,085，配股成本 0；60,085 ÷ 2,020 ＝ 29.745… → 29.75
        assertThat(r.totalCost()).isEqualTo(60_085);
        assertThat(r.avgCost()).isEqualByComparingTo("29.75");
    }

    @Test
    void stockDividendFractionIsDropped_2884() {
        LedgerResult r = Ledger.compute("2884", List.of(buy("2025-07-01", 1550, "30.00")), List.of(ESUN_2025), TODAY);
        assertThat(r.cashDividends()).isEqualTo(1_860);
        // 1,550 × 10 ÷ 1,000 ＝ 15.5 → 15
        assertThat(r.stockDividendShares()).isEqualTo(15);
        assertThat(r.shares()).isEqualTo(1_565);
    }

    @Test
    void otc_8069() {
        LedgerResult r = Ledger.compute("8069", List.of(buy("2025-07-01", 1000, "200")), List.of(EINK_2025), TODAY);
        // 1,000 × 4.99013930 ＝ 4,990.1393 → 4,990
        assertThat(r.cashDividends()).isEqualTo(4_990);
    }

    // ---- 試用回報 ----

    @Test
    void boughtOnExDateGetsNothing_report1() {
        LedgerResult r = Ledger.compute("2330", List.of(buy("2025-06-12", 1000, "1060")), List.of(TSMC_2025Q), TODAY);
        assertThat(r.cashDividends()).isZero();
        assertThat(r.dividends()).isEmpty();
    }

    @Test
    void boughtDayBeforeExDateGetsIt() {
        LedgerResult r = Ledger.compute("2330", List.of(buy("2025-06-11", 1000, "1065")), List.of(TSMC_2025Q), TODAY);
        assertThat(r.cashDividends()).isEqualTo(4_500);
    }

    @Test
    void twentyLotsIsNinetyThousandBeforeNhiPremium_report2() {
        LedgerResult r = Ledger.compute("2330", List.of(buy("2025-01-02", 20_000, "1000")), List.of(TSMC_2025Q), TODAY);
        // 20,000 × 4.50002 ＝ 90,000.4 → 90,000；二代健保補充保費 90,000 × 2.11% ＝ 1,899 → 入帳 88,101
        assertThat(r.cashDividends()).isEqualTo(90_000);
        DividendLine line = r.dividends().get(0);
        assertThat(line.nhiPremiumEstimate()).isEqualTo(1_899);
        assertThat(line.cash() - line.nhiPremiumEstimate()).isEqualTo(88_101);
    }

    @Test
    void nhiPremiumOnlyFrom20000() {
        assertThat(new DividendLine(TODAY, "息", 1, null, BigDecimal.ZERO, 19_999L, 0L).nhiPremiumEstimate()).isZero();
        assertThat(new DividendLine(TODAY, "息", 1, null, BigDecimal.ZERO, 20_000L, 0L).nhiPremiumEstimate()).isEqualTo(422);
        assertThat(new DividendLine(TODAY, "息", 1, null, BigDecimal.ZERO, 20_000_000L, 0L).nhiPremiumEstimate()).isEqualTo(211_000);
    }

    @Test
    void nhiPremiumBaseIncludesStockDividendAtFaceValue() {
        // 玉山金 2025：16,000 股 × 1.2 ＝ 19,200 元現金（未達 2 萬），配股 160 股面額 1,600 元，合計 20,800 元 → 439
        LedgerResult r = Ledger.compute("2884", List.of(buy("2025-07-01", 16_000, "30")), List.of(ESUN_2025), TODAY);
        DividendLine line = r.dividends().get(0);
        assertThat(line.cash()).isEqualTo(19_200);
        assertThat(line.dividendIncome()).isEqualTo(20_800);
        assertThat(line.nhiPremiumEstimate()).isEqualTo(439);
    }

    // ---- 持有期間 ----

    @Test
    void soldOnExDateStillGetsIt_soldBeforeDoesNot() {
        LedgerResult onDay = Ledger.compute("2330",
                List.of(buy("2025-06-02", 1000, "1000"), sell("2025-06-12", 1000, "1060")), List.of(TSMC_2025Q), TODAY);
        assertThat(onDay.cashDividends()).isEqualTo(4_500);
        LedgerResult before = Ledger.compute("2330",
                List.of(buy("2025-06-02", 1000, "1000"), sell("2025-06-11", 1000, "1060")), List.of(TSMC_2025Q), TODAY);
        assertThat(before.cashDividends()).isZero();
    }

    @Test
    void dividendsFollowEditsAndDeletes() {
        Trade early = buy("2025-06-02", 1000, "1000");
        Trade late = buy("2025-06-20", 500, "1000");
        assertThat(Ledger.compute("2330", List.of(early, late), List.of(TSMC_2025Q), TODAY).cashDividends()).isEqualTo(4_500);
        // 把晚買的那筆改到除息前：股利跟著變
        Trade lateMoved = new Trade(late.id(), 1, Side.BUY, LocalDate.parse("2025-06-05"), 500, late.price(), 2);
        assertThat(Ledger.compute("2330", List.of(early, lateMoved), List.of(TSMC_2025Q), TODAY).cashDividends()).isEqualTo(6_750);
        // 刪掉早買的
        assertThat(Ledger.compute("2330", List.of(late), List.of(TSMC_2025Q), TODAY).cashDividends()).isZero();
    }

    @Test
    void futureExRightNotCountedYet() {
        LedgerResult r = Ledger.compute("2330", List.of(buy("2025-06-02", 1000, "1000")), List.of(TSMC_2025Q),
                LocalDate.of(2025, 6, 11));
        assertThat(r.cashDividends()).isZero();
    }

    @Test
    void stockDividendSharesCanBeSold() {
        // 2,000 股＋配 20 股＝2,020 股，全部賣得掉
        LedgerResult r = Ledger.compute("2884",
                List.of(buy("2025-07-01", 2000, "30"), sell("2025-08-01", 2020, "31")), List.of(ESUN_2025), TODAY);
        assertThat(r.shares()).isZero();
        assertThat(r.totalCost()).isZero();
        assertThat(r.avgCost()).isNull();
        // 賣 62,620 − 手續費 89 − 證交稅 187 − 成本 60,085 ＝ 2,259
        assertThat(r.realizedPnl()).isEqualTo(2_259);
    }

    @Test
    void unknownDetailMarksDividendsIncomplete() {
        ExRight unknown = new ExRight("2884", LocalDate.of(2025, 7, 22), "權息", null, null);
        LedgerResult r = Ledger.compute("2884", List.of(buy("2025-07-01", 2000, "30")), List.of(unknown), TODAY);
        assertThat(r.dividendsKnown()).isFalse();
        assertThat(r.dividends().get(0).cash()).isNull();
        assertThat(r.shares()).isEqualTo(2000);
    }

    // ---- 買賣、成本、損益 ----

    @Test
    void averageCostIncludesBuyFeesAcrossBuys() {
        // 台積電分三次買：1000 股 @1000、500 股 @1100、300 股（零股）@1050
        LedgerResult r = Ledger.compute("2330", List.of(
                buy("2024-03-01", 1000, "1000"), buy("2024-05-01", 500, "1100"), buy("2024-07-01", 300, "1050")),
                List.of(), TODAY);
        // 1,000,000＋1,425；550,000＋783；315,000＋448 ＝ 1,867,656
        assertThat(r.shares()).isEqualTo(1_800);
        assertThat(r.totalCost()).isEqualTo(1_867_656);
        assertThat(r.avgCost()).isEqualByComparingTo("1037.59");
    }

    @Test
    void partialSellRealizesAgainstAverageCost() {
        LedgerResult r = Ledger.compute("2330", List.of(
                buy("2024-03-01", 1000, "1000"), buy("2024-05-01", 1000, "1100"), sell("2024-08-01", 500, "1200")),
                List.of(), TODAY);
        // 總成本 1,001,425＋1,101,567＝2,102,992；賣 500 股移出 525,748
        // 賣出 600,000 − 手續費 855 − 證交稅 1,800 ＝ 597,345；已實現 71,597
        assertThat(r.realizedPnl()).isEqualTo(71_597);
        assertThat(r.shares()).isEqualTo(1_500);
        assertThat(r.totalCost()).isEqualTo(2_102_992 - 525_748);
    }

    @Test
    void cannotSellMoreThanHeldAtThatTime() {
        assertThatThrownBy(() -> Ledger.compute("2330", List.of(
                buy("2024-03-01", 1000, "1000"), sell("2024-02-01", 500, "900")), List.of(), TODAY))
                .isInstanceOf(OversellException.class)
                .hasMessageContaining("2024-02-01");
        assertThatThrownBy(() -> Ledger.compute("2330", List.of(
                buy("2024-03-01", 1000, "1000"), sell("2024-04-01", 1001, "900")), List.of(), TODAY))
                .isInstanceOf(OversellException.class);
    }

    @Test
    void sameDayBuyThenSellIsAllowed() {
        LedgerResult r = Ledger.compute("2330", List.of(
                sell("2024-03-01", 1000, "1010"), buy("2024-03-01", 1000, "1000")), List.of(), TODAY);
        assertThat(r.shares()).isZero();
    }

    @Test
    void unrealizedAndTotalReturn() {
        LedgerResult r = Ledger.compute("2330", List.of(buy("2025-06-10", 1000, "1000")), List.of(TSMC_2025Q), TODAY);
        long unrealized = Ledger.unrealized("2330", r.shares(), r.totalCost(), new BigDecimal("1100"));
        // 跟 1.0 的例子一樣：93,708
        assertThat(unrealized).isEqualTo(93_708);
        assertThat(r.realizedPnl() + unrealized + r.cashDividends()).isEqualTo(93_708 + 4_500);
    }

    @Test
    void app10BuyPriceStaysWhatUserTyped() {
        LedgerResult r = Ledger.compute("2330", List.of(buy("2026-11-01", 1000, "1000.00")), List.of(), TODAY);
        assertThat(r.avgPriceExFee()).isEqualByComparingTo("1000.00");
    }

    @Test
    void oddLotSharesFromTpexFractionalRatio() {
        // 6762 2025-06-18 除權息：每仟股無償配股 150.00000327
        ExRight e = new ExRight("6762", LocalDate.of(2025, 6, 18), "除權息", new BigDecimal("0.3"), new BigDecimal("150.00000327"));
        List<Trade> t = new ArrayList<>(List.of(buy("2025-06-01", 1001, "50")));
        LedgerResult r = Ledger.compute("6762", t, List.of(e), TODAY);
        // 1,001 × 150.00000327 ÷ 1,000 ＝ 150.15… → 150；現金 300.3 → 300
        assertThat(r.stockDividendShares()).isEqualTo(150);
        assertThat(r.cashDividends()).isEqualTo(300);
    }
}
