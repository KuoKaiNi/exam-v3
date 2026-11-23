package tw.exam.holdings.quote;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 官方已公布的最近一個交易日收盤價。 */
public record Quote(String code, String name, Market market, LocalDate date, BigDecimal close) {
}
