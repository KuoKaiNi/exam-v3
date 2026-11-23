package tw.exam.holdings.portfolio;

import java.math.BigDecimal;
import java.util.List;

/**
 * 總計（定義照 App 1.0 API 規格）：
 * <ul>
 *   <li>totalCost：每筆（買進金額 + 買進手續費）加總，取不到收盤價的也算進來</li>
 *   <li>marketValue：每筆（收盤價 × 股數）加總，不扣費用；取不到收盤價的不算</li>
 *   <li>pnl：每筆盈虧加總；取不到收盤價的不算</li>
 *   <li>returnPct：pnl ÷ totalCost × 100，四捨五入到小數第二位</li>
 *   <li>quoteMissing：取不到收盤價的筆數</li>
 * </ul>
 */
public record Summary(long totalCost, long marketValue, long pnl, BigDecimal returnPct, int quoteMissing) {

    public static Summary of(List<Position> positions) {
        long cost = 0;
        long value = 0;
        long pnl = 0;
        int missing = 0;
        for (Position p : positions) {
            cost += p.cost();
            if (p.pnl() == null) {
                missing++;
            } else {
                value += p.pnl().sellAmount();
                pnl += p.pnl().pnl();
            }
        }
        return new Summary(cost, value, pnl, PnlCalculator.returnPct(pnl, cost), missing);
    }
}
