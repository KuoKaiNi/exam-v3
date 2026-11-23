package tw.exam.holdings.portfolio;

import tw.exam.holdings.holding.Holding;
import tw.exam.holdings.quote.Quote;

/** 一筆持股加上快取裡的收盤價與盈虧；quote、pnl 為 null 表示「未取得」。 */
public record Position(Holding holding, Quote quote, PnlCalculator.Pnl pnl) {

    public static Position of(Holding h, Quote quote) {
        PnlCalculator.Pnl pnl = quote == null ? null
                : PnlCalculator.calculate(h.code(), h.lots(), h.buyPrice(), quote.close());
        return new Position(h, quote, pnl);
    }

    public String name() {
        if (holding.name() != null) {
            return holding.name();
        }
        return quote != null ? quote.name() : "";
    }

    public long cost() {
        return pnl != null ? pnl.cost() : PnlCalculator.cost(holding.lots(), holding.buyPrice());
    }
}
