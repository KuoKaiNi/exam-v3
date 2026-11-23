package tw.exam.holdings.portfolio;

import java.util.List;

/**
 * 2.0 的總計。未實現、市值只算有收盤價的；priceMissing 是有股數但沒有收盤價的檔數。
 * totalReturn ＝ realizedPnl ＋ unrealizedPnl ＋ cashDividends（priceMissing 大於 0 時偏低）。
 */
public record SummaryV2(long totalCost, long marketValue, long unrealizedPnl, long realizedPnl, long cashDividends,
                        long totalReturn, int priceMissing, int dividendDataIncomplete) {

    public static SummaryV2 of(List<Position> positions) {
        long cost = 0;
        long value = 0;
        long unrealized = 0;
        long realized = 0;
        long cash = 0;
        int missing = 0;
        int incomplete = 0;
        for (Position p : positions) {
            cost += p.ledger().totalCost();
            realized += p.ledger().realizedPnl();
            cash += p.ledger().cashDividends();
            if (p.priceMissing()) {
                missing++;
            } else {
                value += p.marketValue();
                unrealized += p.unrealized();
            }
            if (!p.dividendDataComplete()) {
                incomplete++;
            }
        }
        return new SummaryV2(cost, value, unrealized, realized, cash, realized + unrealized + cash, missing, incomplete);
    }
}
