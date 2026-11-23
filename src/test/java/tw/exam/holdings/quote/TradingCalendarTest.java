package tw.exam.holdings.quote;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

class TradingCalendarTest {

    static ZonedDateTime tpe(int m, int d, int h, int min) {
        return ZonedDateTime.of(2026, m, d, h, min, 0, 0, ZoneId.of("Asia/Taipei"));
    }

    @Test
    void weekdayAfterCloseIsToday() {
        assertThat(TradingCalendar.expectedLatest(tpe(11, 23, 13, 30))).hasToString("2026-11-23");
    }

    @Test
    void weekdayBeforeCloseIsPreviousWeekday() {
        // 11/23 是星期一，前一個平日是 11/20 星期五
        assertThat(TradingCalendar.expectedLatest(tpe(11, 23, 13, 29))).hasToString("2026-11-20");
    }

    @Test
    void weekendIsFriday() {
        assertThat(TradingCalendar.expectedLatest(tpe(11, 22, 20, 0))).hasToString("2026-11-20");
    }

    @Test
    void usesTaipeiDateEvenWhenServerIsElsewhere() {
        // 紐約 11/23 01:00 = 台北 11/23 14:00
        ZonedDateTime ny = ZonedDateTime.of(2026, 11, 23, 1, 0, 0, 0, ZoneId.of("America/New_York"));
        assertThat(TradingCalendar.expectedLatest(ny.withZoneSameInstant(ZoneId.of("Asia/Taipei"))))
                .hasToString("2026-11-23");
    }
}
