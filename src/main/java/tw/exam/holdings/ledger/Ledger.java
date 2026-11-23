package tw.exam.holdings.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import tw.exam.holdings.portfolio.PnlCalculator;

/**
 * 依時間軸算一檔股票的股數、成本、已實現損益與除權息。
 *
 * <p>同一天的處理順序：
 * <ol>
 *   <li>先處理當天的除權息：領得到的股數＝前一天結束時的股數（交易只會發生在交易日，所以「除權息日之前的所有交易」就是
 *       「前一個交易日收盤時」的持股）。除權息當天才買的領不到，當天賣的照領。配股當天入帳。</li>
 *   <li>再處理當天的交易：先買後賣（允許當沖），同一種依 id 先後。</li>
 * </ol>
 *
 * <p>成本用平均成本法：賣出時移出的成本＝剩餘總成本 × 賣出股數 ÷ 賣出前股數，四捨五入到元。
 */
public final class Ledger {

    private Ledger() {
    }

    /**
     * @param today 台北時間的今天；除權息日在今天之後的不算
     * @throws OversellException 某天賣超過當時的持股
     */
    public static LedgerResult compute(String code, List<Trade> trades, List<ExRight> events, LocalDate today) {
        TreeSet<LocalDate> days = new TreeSet<>();
        trades.forEach(t -> days.add(t.date()));
        LocalDate first = days.isEmpty() ? null : days.first();
        List<ExRight> relevant = events.stream()
                .filter(e -> first != null && e.exDate().isAfter(first) && !e.exDate().isAfter(today))
                .sorted(Comparator.comparing(ExRight::exDate))
                .toList();
        relevant.forEach(e -> days.add(e.exDate()));

        long shares = 0;
        long cost = 0;
        long costExFee = 0;
        long realized = 0;
        long cashTotal = 0;
        long stockTotal = 0;
        boolean known = true;
        List<DividendLine> lines = new ArrayList<>();
        Map<Long, Long> realizedBySell = new LinkedHashMap<>();

        for (LocalDate day : days) {
            for (ExRight e : relevant) {
                if (!e.exDate().equals(day) || shares == 0) {
                    continue;
                }
                if (!e.known()) {
                    known = false;
                    lines.add(new DividendLine(day, e.kind(), shares, e.cashPerShare(), e.stockPerThousand(), null, null));
                    continue;
                }
                long cash = BigDecimal.valueOf(shares).multiply(e.cashPerShare())
                        .setScale(0, RoundingMode.FLOOR).longValueExact();
                long stock = BigDecimal.valueOf(shares).multiply(e.stockPerThousand())
                        .divide(BigDecimal.valueOf(1000), 0, RoundingMode.FLOOR).longValueExact();
                lines.add(new DividendLine(day, e.kind(), shares, e.cashPerShare(), e.stockPerThousand(), cash, stock));
                cashTotal += cash;
                stockTotal += stock;
                shares += stock;
            }
            List<Trade> today_ = trades.stream()
                    .filter(t -> t.date().equals(day))
                    .sorted(Comparator.comparing((Trade t) -> t.side() == Side.BUY ? 0 : 1).thenComparing(Trade::id))
                    .toList();
            for (Trade t : today_) {
                long amount = PnlCalculator.amount(t.price(), t.shares());
                if (t.side() == Side.BUY) {
                    long fee = PnlCalculator.fee(amount);
                    cost += amount + fee;
                    costExFee += amount;
                    shares += t.shares();
                } else {
                    if (t.shares() > shares) {
                        throw new OversellException(day, shares, t.shares());
                    }
                    long proceeds = amount - PnlCalculator.fee(amount) - PnlCalculator.tax(code, amount);
                    long removed = share(cost, t.shares(), shares);
                    long removedExFee = share(costExFee, t.shares(), shares);
                    long pnl = proceeds - removed;
                    realizedBySell.put(t.id(), pnl);
                    realized += pnl;
                    cost -= removed;
                    costExFee -= removedExFee;
                    shares -= t.shares();
                }
            }
        }
        return new LedgerResult(shares, cost, costExFee, realized, cashTotal, stockTotal, List.copyOf(lines),
                realizedBySell, known);
    }

    /** total × part ÷ whole，四捨五入到元；全部賣出時剛好等於 total。 */
    private static long share(long total, long part, long whole) {
        return BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(part))
                .divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * 假設用收盤價賣掉剩下的股數，扣掉手續費和證交稅後跟剩餘成本比，賺多少。沒有股數時是 0。
     */
    public static long unrealized(String code, long shares, long totalCost, BigDecimal close) {
        if (shares == 0) {
            return 0;
        }
        long amount = PnlCalculator.amount(close, shares);
        return amount - PnlCalculator.fee(amount) - PnlCalculator.tax(code, amount) - totalCost;
    }
}
