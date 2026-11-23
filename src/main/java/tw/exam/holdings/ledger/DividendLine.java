package tw.exam.holdings.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 持有期間的一次除權息。
 *
 * @param eligibleShares 除權息交易日前一個交易日收盤時持有的股數
 * @param cash           現金股利（元以下捨去）；明細未取得時為 null
 * @param stockShares    配股（不足一股捨去）；明細未取得時為 null
 */
public record DividendLine(LocalDate exDate, String kind, long eligibleShares, BigDecimal cashPerShare,
                           BigDecimal stockPerThousand, Long cash, Long stockShares) {

    public boolean known() {
        return cash != null;
    }

    /**
     * 股利所得（補充保費的計算基礎）＝現金股利＋股票股利面額。股票股利以面額每股 10 元計：
     * 領取股數 × 每千股配股 ÷ 1,000 × 10 元，元以下捨去。
     */
    public long dividendIncome() {
        if (cash == null) {
            return 0;
        }
        long stockFace = stockPerThousand == null ? 0
                : BigDecimal.valueOf(eligibleShares).multiply(stockPerThousand)
                        .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.FLOOR).longValueExact();
        return cash + stockFace;
    }

    /**
     * 二代健保補充保費（預估）：單次股利所得（含現金股利及股票股利）2 萬元以上，× 2.11%，
     * 單次超過 1,000 萬元以 1,000 萬元計，四捨五入到元。依投保身分可能不扣或算法不同，所以只是預估。
     */
    public long nhiPremiumEstimate() {
        long income = dividendIncome();
        if (income < 20_000) {
            return 0;
        }
        long base = Math.min(income, 10_000_000L);
        return BigDecimal.valueOf(base).multiply(new BigDecimal("0.0211"))
                .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }
}
