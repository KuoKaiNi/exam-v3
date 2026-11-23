package tw.exam.holdings.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 一筆買進或賣出。股數可以是零股；version 用來擋兩台裝置同時修改。 */
public record Trade(long id, long holdingId, Side side, LocalDate date, long shares, BigDecimal price, int version) {
}
