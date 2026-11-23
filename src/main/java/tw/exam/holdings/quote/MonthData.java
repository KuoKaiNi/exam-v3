package tw.exam.holdings.quote;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 某檔股票某個月的官方日成交資料。
 *
 * <ul>
 *   <li>FOUND：官方有這檔、這個月有資料</li>
 *   <li>EMPTY：官方正常回應，但沒有資料（代號不存在，或這個月還沒有交易日）</li>
 *   <li>FAILED：連不上、逾時、被擋、回應看不懂；不能拿來判斷代號存不存在</li>
 * </ul>
 */
public record MonthData(Status status, String name, List<DailyClose> days, String failure) {

    public enum Status { FOUND, EMPTY, FAILED }

    public static MonthData found(String name, List<DailyClose> days) {
        return new MonthData(Status.FOUND, name, List.copyOf(days), null);
    }

    public static MonthData empty() {
        return new MonthData(Status.EMPTY, null, List.of(), null);
    }

    public static MonthData failed(String reason) {
        return new MonthData(Status.FAILED, null, List.of(), reason);
    }

    /** 這個月最後一個有收盤價的交易日。 */
    public Optional<DailyClose> latest() {
        return days.stream().max(Comparator.comparing(DailyClose::date));
    }
}
