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

    /** 二代健保補充保費：單次股利 2 萬元以上，全額 × 2.11%（上限 1,000 萬元），四捨五入到元。這是預估，免扣繳身分不適用。 */
    public long nhiPremiumEstimate() {
        if (cash == null || cash < 20_000) {
            return 0;
        }
        long base = Math.min(cash, 10_000_000L);
        return BigDecimal.valueOf(base).multiply(new BigDecimal("0.0211"))
                .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }
}
