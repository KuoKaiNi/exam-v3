package tw.exam.holdings.quote;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyClose(LocalDate date, BigDecimal close) {
}
