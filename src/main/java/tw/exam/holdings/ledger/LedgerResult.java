package tw.exam.holdings.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 一檔股票照時間軸算完的結果（不含收盤價）。
 *
 * @param shares              目前股數（含配股）
 * @param totalCost           剩餘總成本（元，含買進手續費；配股成本 0）
 * @param costExFee           剩餘總成本扣掉買進手續費的部分（給 App 1.0 的 buy_price 用）
 * @param realizedPnl         已實現損益合計
 * @param cashDividends       累計現金股利
 * @param stockDividendShares 累計配股股數
 * @param realizedBySell      每筆賣出各自的已實現損益（key 是交易 id）
 * @param dividendsKnown      持有期間的除權息明細是不是都拿到了
 */
public record LedgerResult(long shares, long totalCost, long costExFee, long realizedPnl, long cashDividends,
                           long stockDividendShares, List<DividendLine> dividends, Map<Long, Long> realizedBySell,
                           boolean dividendsKnown) {

    /** 平均成本＝剩餘總成本 ÷ 股數，四捨五入到小數第二位；沒有股數時為 null。 */
    public BigDecimal avgCost() {
        if (shares == 0) {
            return null;
        }
        return BigDecimal.valueOf(totalCost).divide(BigDecimal.valueOf(shares), 2, RoundingMode.HALF_UP);
    }

    /** 不含手續費的平均買進價，給 App 1.0 的 buy_price。 */
    public BigDecimal avgPriceExFee() {
        if (shares == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(costExFee).divide(BigDecimal.valueOf(shares), 2, RoundingMode.HALF_UP);
    }
}
