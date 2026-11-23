package tw.exam.holdings.quote;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * 判斷「現在應該查得到哪一天的收盤價」。只看週末，不知道國定假日：
 * 遇到假日會以為當天該有資料，結果就是那天多查幾次（間隔 staleRetry），不會算錯。
 */
public final class TradingCalendar {

    public static final LocalTime CLOSE = LocalTime.of(13, 30);

    private TradingCalendar() {
    }

    /** now 必須是台北時間。平日 13:30 以後是今天，否則是前一個平日。 */
    public static LocalDate expectedLatest(ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        if (isWeekday(today) && !now.toLocalTime().isBefore(CLOSE)) {
            return today;
        }
        LocalDate d = today.minusDays(1);
        while (!isWeekday(d)) {
            d = d.minusDays(1);
        }
        return d;
    }

    private static boolean isWeekday(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
