package tw.exam.holdings.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import tw.exam.holdings.holding.Holding;
import tw.exam.holdings.ledger.Ledger;
import tw.exam.holdings.ledger.LedgerResult;
import tw.exam.holdings.ledger.Trade;
import tw.exam.holdings.quote.Quote;

/**
 * 一檔股票的現況：交易算出來的結果＋快取裡的收盤價。quote 為 null 表示「未取得」。
 *
 * @param dividendDataComplete 持有期間的官方除權息資料都拿到了（沒拿到時股利可能少算）
 */
public record Position(Holding holding, List<Trade> trades, LedgerResult ledger, Quote quote,
                       boolean dividendDataComplete) {

    public String name() {
        if (holding.name() != null) {
            return holding.name();
        }
        return quote != null ? quote.name() : "";
    }

    public long shares() {
        return ledger.shares();
    }

    public boolean open() {
        return ledger.shares() > 0;
    }

    /** 有股數又沒有收盤價時算不出來。 */
    public boolean priceMissing() {
        return open() && quote == null;
    }

    /** 未實現損益：假設用收盤價把剩下的全部賣掉，扣手續費、證交稅後減剩餘成本。 */
    public Long unrealized() {
        if (!open()) {
            return 0L;
        }
        return quote == null ? null : Ledger.unrealized(holding.code(), ledger.shares(), ledger.totalCost(), quote.close());
    }

    /** 未實現報酬率（%）＝未實現 ÷ 剩餘成本，跟 1.0 的收益率同一個定義。 */
    public BigDecimal unrealizedPct() {
        Long u = unrealized();
        if (u == null || ledger.totalCost() == 0) {
            return u == null ? null : BigDecimal.ZERO.setScale(2);
        }
        return PnlCalculator.returnPct(u, ledger.totalCost());
    }

    /** 市值＝收盤價 × 股數，不扣費用。 */
    public Long marketValue() {
        if (!open()) {
            return 0L;
        }
        return quote == null ? null : PnlCalculator.amount(quote.close(), ledger.shares());
    }

    /** 含息總報酬＝已實現＋未實現＋累計現金股利。 */
    public Long totalReturn() {
        Long u = unrealized();
        return u == null ? null : ledger.realizedPnl() + u + ledger.cashDividends();
    }

    /** App 1.0 的 lots：只能是整數，零股的部分捨去（盈虧照實際股數算）。 */
    public int v1Lots() {
        return (int) (ledger.shares() / 1000);
    }

    static BigDecimal pct(long part, long whole) {
        return whole == 0 ? BigDecimal.ZERO.setScale(2)
                : BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(whole), 2, RoundingMode.HALF_UP);
    }
}
