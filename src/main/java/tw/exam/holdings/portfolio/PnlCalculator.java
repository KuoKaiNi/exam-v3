package tw.exam.holdings.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 「假設現在用收盤價全部賣出，淨賺多少」。
 *
 * <ul>
 *   <li>手續費：成交金額 × 0.1425%，無條件捨去到元，每筆最低 20 元；買進、賣出各收一次</li>
 *   <li>證交稅：只有賣出收；一般股票 0.3%，ETF（代號 00 開頭）0.1%；無條件捨去到元</li>
 *   <li>盈虧 = 賣出金額 − 賣出手續費 − 證交稅 − 買進金額 − 買進手續費</li>
 *   <li>收益率 = 盈虧 ÷（買進金額 + 買進手續費）× 100，四捨五入到小數第二位</li>
 * </ul>
 * 券商的手續費折扣不算（題目沒有）。
 */
public final class PnlCalculator {

    static final BigDecimal FEE_RATE = new BigDecimal("0.001425");
    static final long MIN_FEE = 20;
    static final BigDecimal TAX_STOCK = new BigDecimal("0.003");
    static final BigDecimal TAX_ETF = new BigDecimal("0.001");
    static final long SHARES_PER_LOT = 1000;

    private PnlCalculator() {
    }

    public record Pnl(long buyAmount, long buyFee, long sellAmount, long sellFee, long tax, long pnl,
                      BigDecimal returnPct) {

        /** 成本 = 買進金額 + 買進手續費 */
        public long cost() {
            return buyAmount + buyFee;
        }
    }

    public static Pnl calculate(String code, int lots, BigDecimal buyPrice, BigDecimal closePrice) {
        long shares = lots * SHARES_PER_LOT;
        long buyAmount = amount(buyPrice, shares);
        long buyFee = fee(buyAmount);
        long sellAmount = amount(closePrice, shares);
        long sellFee = fee(sellAmount);
        long tax = tax(code, sellAmount);
        long pnl = sellAmount - sellFee - tax - buyAmount - buyFee;
        return new Pnl(buyAmount, buyFee, sellAmount, sellFee, tax, pnl, returnPct(pnl, buyAmount + buyFee));
    }

    /** 買進成本（買進金額 + 買進手續費），取不到收盤價時總計也要用。 */
    public static long cost(int lots, BigDecimal buyPrice) {
        long buyAmount = amount(buyPrice, lots * SHARES_PER_LOT);
        return buyAmount + fee(buyAmount);
    }

    /** 成交金額＝價格 × 股數。價格最多兩位小數、股數是 1000 的倍數，所以一定是整數元。 */
    static long amount(BigDecimal price, long shares) {
        return price.multiply(BigDecimal.valueOf(shares)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    static long fee(long amount) {
        long fee = BigDecimal.valueOf(amount).multiply(FEE_RATE).setScale(0, RoundingMode.FLOOR).longValueExact();
        return Math.max(fee, MIN_FEE);
    }

    static long tax(String code, long sellAmount) {
        BigDecimal rate = isEtf(code) ? TAX_ETF : TAX_STOCK;
        return BigDecimal.valueOf(sellAmount).multiply(rate).setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    public static boolean isEtf(String code) {
        return code.startsWith("00");
    }

    /** 盈虧 ÷ 成本 × 100，四捨五入到小數第二位（負數也是離 0 遠的那邊進位：−3.375 → −3.38）。 */
    public static BigDecimal returnPct(long pnl, long cost) {
        if (cost == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(pnl).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(cost), 2, RoundingMode.HALF_UP);
    }
}
