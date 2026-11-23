package tw.exam.holdings.dividend;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import tw.exam.holdings.ledger.ExRight;

/**
 * 解析官方的除權除息計算結果表。兩邊格式不一樣（實際抓下來看的，2026-11-23）：
 *
 * <ul>
 *   <li>證交所 TWT49U：日期「114年06月12日」；只有「權值+息值」一欄，沒有分開的每股現金股利和配股。
 *       權/息欄是「息」時，權值+息值就是每股現金股利；「權」「權息」要另外查明細 TWT49UDetail。</li>
 *   <li>櫃買中心 exDailyQ：日期「114/07/24」；直接有「現金股利」「每仟股無償配股」兩欄；權/息欄是「除息」「除權」「除權息」。</li>
 * </ul>
 */
public final class ExRightParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ROC = Pattern.compile("(\\d{2,3})\\D+(\\d{1,2})\\D+(\\d{1,2})");
    private static final Pattern NUMBER = Pattern.compile("-?[\\d,]*\\.?\\d+");

    private ExRightParser() {
    }

    /** 解析結果；ok 為 false 表示回應看不懂（不是「沒有資料」）。 */
    public record Parsed(boolean ok, List<ExRight> rows) {
    }

    public static Parsed parseTwse(String body) {
        JsonNode root = read(body);
        if (root == null || !root.isObject() || !root.has("stat")) {
            return new Parsed(false, List.of());
        }
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            return new Parsed(true, List.of());
        }
        JsonNode fields = root.path("fields");
        int iDate = index(fields, "資料日期", 0);
        int iCode = index(fields, "股票代號", 1);
        int iValue = index(fields, "權值+息值", 5);
        int iKind = index(fields, "權/息", 6);
        List<ExRight> rows = new ArrayList<>();
        for (JsonNode r : data) {
            LocalDate date = rocDate(r.path(iDate).asText(""));
            String code = r.path(iCode).asText("").strip();
            String kind = r.path(iKind).asText("").strip();
            if (date == null || code.isEmpty() || kind.isEmpty()) {
                continue;
            }
            if ("息".equals(kind)) {
                // 除息：權值+息值 ＝ 除息前收盤價 − 除息參考價 ＝ 每股現金股利
                BigDecimal cash = number(r.path(iValue).asText("")).orElse(null);
                rows.add(new ExRight(code, date, kind, cash, cash == null ? null : BigDecimal.ZERO));
            } else {
                rows.add(new ExRight(code, date, kind, null, null));
            }
        }
        return new Parsed(true, rows);
    }

    public static Parsed parseTpex(String body) {
        JsonNode root = read(body);
        if (root == null || !root.isObject() || !root.has("tables")) {
            return new Parsed(false, List.of());
        }
        JsonNode table = root.path("tables").path(0);
        JsonNode fields = table.path("fields");
        int iDate = index(fields, "除權息日期", 0);
        int iCode = index(fields, "代號", 1);
        int iKind = index(fields, "權/息", 8);
        int iCash = index(fields, "現金股利", 13);
        int iStock = index(fields, "每仟股無償配股", 14);
        List<ExRight> rows = new ArrayList<>();
        for (JsonNode r : table.path("data")) {
            LocalDate date = rocDate(r.path(iDate).asText(""));
            String code = r.path(iCode).asText("").strip();
            if (date == null || code.isEmpty()) {
                continue;
            }
            BigDecimal cash = number(r.path(iCash).asText("")).orElse(null);
            BigDecimal stock = number(r.path(iStock).asText("")).orElse(null);
            rows.add(new ExRight(code, date, r.path(iKind).asText("").strip(), cash, stock));
        }
        return new Parsed(true, rows);
    }

    /** 證交所明細：「1.2 元／股」「10 股」。回傳 [每股現金股利, 每千股無償配股]；查無資料回 empty。 */
    public static Optional<BigDecimal[]> parseTwseDetail(String body) {
        JsonNode root = read(body);
        if (root == null) {
            return Optional.empty();
        }
        JsonNode row = root.path("data").path(0);
        if (!row.isArray()) {
            return Optional.empty();
        }
        JsonNode fields = root.path("fields");
        int iCash = index(fields, "(每股配發現金股利)", 2);
        int iStock = index(fields, "A. 按普通股股東持股比例每千股無償配股", 4);
        BigDecimal cash = number(row.path(iCash).asText("")).orElse(BigDecimal.ZERO);
        BigDecimal stock = number(row.path(iStock).asText("")).orElse(BigDecimal.ZERO);
        return Optional.of(new BigDecimal[] {cash, stock});
    }

    static LocalDate rocDate(String text) {
        Matcher m = ROC.matcher(text);
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(m.group(1)) + 1911, Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Optional<BigDecimal> number(String text) {
        Matcher m = NUMBER.matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(m.group().replace(",", "")));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static int index(JsonNode fields, String name, int fallback) {
        if (fields.isArray()) {
            for (int i = 0; i < fields.size(); i++) {
                if (fields.get(i).asText("").strip().startsWith(name)) {
                    return i;
                }
            }
        }
        return fallback;
    }

    private static JsonNode read(String body) {
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
