package tw.exam.holdings.quote;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 解析證交所 STOCK_DAY 與櫃買中心 tradingStock 的 JSON。
 * 日期是民國年（115/11/02 = 2026-11-02），價格有千分位逗號，沒成交的日子收盤價是 "--"。
 */
public final class OfficialResponseParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    // 證交所 title：「115年11月 2330 台積電           各日成交資訊」
    private static final Pattern TWSE_TITLE = Pattern.compile("^\\S+\\s+(\\S+)\\s+(.+?)\\s+各日成交資訊\\s*$");
    private static final Pattern ROC_DATE = Pattern.compile("(\\d{2,3})/(\\d{1,2})/(\\d{1,2})");

    private OfficialResponseParser() {
    }

    public static MonthData parseTwse(String body, String code) {
        JsonNode root = readJson(body);
        if (root == null || !root.isObject() || !root.has("stat")) {
            return MonthData.failed("證交所回應不是預期的 JSON");
        }
        JsonNode data = root.path("data");
        if (!"OK".equalsIgnoreCase(root.path("stat").asText()) || !data.isArray() || data.isEmpty()) {
            // 查無代號時 stat 是「很抱歉，沒有符合條件的資料!」
            return MonthData.empty();
        }
        String name = code;
        Matcher m = TWSE_TITLE.matcher(root.path("title").asText(""));
        if (m.matches()) {
            name = m.group(2).strip();
        }
        int closeIndex = indexOf(root.path("fields"), "收盤", 6);
        return days(name, data, closeIndex);
    }

    public static MonthData parseTpex(String body, String code) {
        JsonNode root = readJson(body);
        if (root == null || !root.isObject() || !root.has("tables")) {
            return MonthData.failed("櫃買中心回應不是預期的 JSON");
        }
        JsonNode table = root.path("tables").path(0);
        JsonNode data = table.path("data");
        if (!data.isArray() || data.isEmpty()) {
            // 查無代號時 stat 是 ok，但 name 空、data 空
            return MonthData.empty();
        }
        String name = root.path("name").asText("").strip();
        if (name.isEmpty()) {
            // subtitle：「6488 環球晶 115年11月」
            String[] parts = table.path("subtitle").asText("").strip().split("\\s+");
            name = parts.length >= 3 ? parts[1] : code;
        }
        int closeIndex = indexOf(table.path("fields"), "收盤", 6);
        return days(name, data, closeIndex);
    }

    private static MonthData days(String name, JsonNode data, int closeIndex) {
        List<DailyClose> days = new ArrayList<>();
        for (JsonNode row : data) {
            LocalDate date = rocDate(row.path(0).asText(""));
            BigDecimal close = price(row.path(closeIndex).asText(""));
            if (date != null && close != null) {
                days.add(new DailyClose(date, close));
            }
        }
        // 整個月都沒成交（例如停牌）：代號存在，但沒有收盤價可用
        return MonthData.found(name, days);
    }

    static LocalDate rocDate(String text) {
        Matcher m = ROC_DATE.matcher(text);
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(m.group(1)) + 1911,
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static BigDecimal price(String text) {
        String t = text.replace(",", "").strip();
        if (t.isEmpty() || t.startsWith("-") && t.chars().allMatch(c -> c == '-')) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(t);
            return v.signum() > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int indexOf(JsonNode fields, String keyword, int fallback) {
        if (fields.isArray()) {
            for (int i = 0; i < fields.size(); i++) {
                if (fields.get(i).asText("").replace(" ", "").startsWith(keyword)) {
                    return i;
                }
            }
        }
        return fallback;
    }

    private static JsonNode readJson(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }
}
