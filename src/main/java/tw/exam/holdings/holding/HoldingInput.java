package tw.exam.holdings.holding;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import tw.exam.holdings.AppException;

/**
 * 持股欄位的檢查規則。網頁表單（字串）和 API（JSON 型別）都走這裡，規則只寫一份。
 *
 * <ul>
 *   <li>股票代號：必填。4 碼數字開頭，後面最多再 2 碼數字或英文（2330、0050、00631L）。</li>
 *   <li>張數：整數，1 ～ 999。</li>
 *   <li>買進價：大於 0，最多兩位小數；欄位是 DECIMAL(10,2)，上限 99,999,999.99。</li>
 * </ul>
 */
public final class HoldingInput {

    private static final Pattern CODE = Pattern.compile("^[0-9]{4}[0-9A-Z]{0,2}$");
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");

    private HoldingInput() {
    }

    /** 收集所有欄位的錯誤，一次回給使用者。 */
    public static final class Errors {
        private final List<String> messages = new ArrayList<>();

        public void add(String message) {
            messages.add(message);
        }

        public void throwIfAny() {
            if (!messages.isEmpty()) {
                throw AppException.validation(String.join("；", messages));
            }
        }
    }

    /** 代號：去頭尾空白、英文轉大寫。不合規則回 null 並記錯誤。 */
    public static String code(String raw, Errors errors) {
        if (raw == null || raw.isBlank()) {
            errors.add("股票代號必填");
            return null;
        }
        // 全形「２３３０」轉成半形再檢查
        String code = Normalizer.normalize(raw, Normalizer.Form.NFKC).strip().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            errors.add("股票代號格式不對，要 4 到 6 碼，例如 2330、0050、00631L");
            return null;
        }
        return code;
    }

    /** 張數：value 為 null 表示沒填；notNumber 表示填的不是數字。 */
    public static Integer lots(BigDecimal value, boolean notNumber, Errors errors) {
        if (notNumber) {
            errors.add("張數要填整數");
            return null;
        }
        if (value == null) {
            errors.add("張數必填");
            return null;
        }
        BigDecimal v = value.stripTrailingZeros();
        if (v.scale() > 0) {
            errors.add("張數要填整數");
            return null;
        }
        if (v.compareTo(BigDecimal.ONE) < 0 || v.compareTo(BigDecimal.valueOf(999)) > 0) {
            errors.add("張數要在 1 到 999 之間");
            return null;
        }
        return v.intValueExact();
    }

    public static BigDecimal buyPrice(BigDecimal value, boolean notNumber, Errors errors) {
        if (notNumber) {
            errors.add("買進價要填數字");
            return null;
        }
        if (value == null) {
            errors.add("買進價必填");
            return null;
        }
        if (value.signum() <= 0) {
            errors.add("買進價要大於 0");
            return null;
        }
        if (value.stripTrailingZeros().scale() > 2) {
            errors.add("買進價最多兩位小數");
            return null;
        }
        if (value.compareTo(MAX_PRICE) > 0) {
            errors.add("買進價太大，上限 99,999,999.99");
            return null;
        }
        return value.setScale(2);
    }

    /** 2.0 每筆交易的股數：整數，1 ～ 999,999（零股可以）。 */
    public static Long shares(BigDecimal value, boolean notNumber, Errors errors) {
        if (notNumber) {
            errors.add("股數要填整數");
            return null;
        }
        if (value == null) {
            errors.add("股數必填");
            return null;
        }
        BigDecimal v = value.stripTrailingZeros();
        if (v.scale() > 0) {
            errors.add("股數要填整數");
            return null;
        }
        if (v.compareTo(BigDecimal.ONE) < 0 || v.compareTo(BigDecimal.valueOf(999_999)) > 0) {
            errors.add("股數要在 1 到 999,999 之間");
            return null;
        }
        return v.longValueExact();
    }

    /** 交易價格：規則跟 1.0 的買進價一樣。 */
    public static BigDecimal price(BigDecimal value, boolean notNumber, Errors errors) {
        Errors local = new Errors();
        BigDecimal r = buyPrice(value, notNumber, local);
        local.messages.forEach(m -> errors.add(m.replace("買進價", "價格")));
        return r;
    }

    /** 最早可以記的交易日（官方除權息資料從民國 92 年 5 月 5 日開始）。 */
    public static final java.time.LocalDate EARLIEST_TRADE_DATE = java.time.LocalDate.of(2003, 5, 5);

    /** 交易日期：YYYY-MM-DD，不能晚於今天（台北時間），不能早於 2003-05-05。 */
    public static java.time.LocalDate date(String text, java.time.LocalDate today, Errors errors) {
        if (text == null || text.isBlank()) {
            errors.add("交易日期必填");
            return null;
        }
        java.time.LocalDate d;
        try {
            d = java.time.LocalDate.parse(text.strip());
        } catch (java.time.format.DateTimeParseException e) {
            errors.add("交易日期格式要是 YYYY-MM-DD，例如 2025-06-10");
            return null;
        }
        if (d.isAfter(today)) {
            errors.add("交易日期不能晚於今天（" + today + "）");
            return null;
        }
        if (d.isBefore(EARLIEST_TRADE_DATE)) {
            errors.add("交易日期不能早於 " + EARLIEST_TRADE_DATE + "（官方除權息資料從這天開始）");
            return null;
        }
        return d;
    }

    public static tw.exam.holdings.ledger.Side side(String text, Errors errors) {
        if (text == null || text.isBlank()) {
            errors.add("買賣別必填（BUY 或 SELL）");
            return null;
        }
        try {
            return tw.exam.holdings.ledger.Side.valueOf(text.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add("買賣別要是 BUY 或 SELL");
            return null;
        }
    }

    /** 網頁表單的文字轉數字；空白回 null，看不懂回 NOT_A_NUMBER。 */
    public static ParsedNumber parseText(String text) {
        if (text == null || text.isBlank()) {
            return new ParsedNumber(null, false);
        }
        try {
            return new ParsedNumber(new BigDecimal(text.strip().replace(",", "")), false);
        } catch (NumberFormatException e) {
            return new ParsedNumber(null, true);
        }
    }

    public record ParsedNumber(BigDecimal value, boolean notNumber) {
    }
}
