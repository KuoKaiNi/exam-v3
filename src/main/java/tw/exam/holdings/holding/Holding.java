package tw.exam.holdings.holding;

import java.math.BigDecimal;

public record Holding(long id, String code, int lots, BigDecimal buyPrice) {

    public long shares() {
        return lots * 1000L;
    }
}
