package tw.exam.holdings.portfolio;

import java.math.BigDecimal;
import java.util.List;

/**
 * App 1.0 的總計（定義照 1.0 規格，只算還持有中的股票）：
 * totalCost＝剩餘成本加總；marketValue、pnl 只算有收盤價的；pnl 是未實現損益；quoteMissing＝沒有收盤價的筆數。
 */
public record Summary(long totalCost, long marketValue, long pnl, BigDecimal returnPct, int quoteMissing) {

    public static Summary of(List<Position> positions) {
        long cost = 0;
        long value = 0;
        long pnl = 0;
        int missing = 0;
        for (Position p : positions) {
            if (!p.open()) {
                continue;
            }
            cost += p.ledger().totalCost();
            if (p.quote() == null) {
                missing++;
            } else {
                value += p.marketValue();
                pnl += p.unrealized();
            }
        }
        return new Summary(cost, value, pnl, PnlCalculator.returnPct(pnl, cost), missing);
    }
}
