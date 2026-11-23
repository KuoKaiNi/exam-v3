package tw.exam.holdings.holding;

import java.math.BigDecimal;

import tw.exam.holdings.quote.Market;

/** name、market 在第 3 題之前建的資料可能是 null，背景更新時會補上。 */
public record Holding(long id, String code, String name, Market market, int lots, BigDecimal buyPrice) {

    public long shares() {
        return lots * 1000L;
    }
}
