package tw.exam.holdings.portfolio;

import tw.exam.holdings.holding.Holding;
import tw.exam.holdings.quote.Quote;

/** 一筆持股加上快取裡的收盤價；quote 為 null 表示「未取得」。 */
public record Position(Holding holding, Quote quote) {

    public String name() {
        if (holding.name() != null) {
            return holding.name();
        }
        return quote != null ? quote.name() : "";
    }
}
