package tw.exam.holdings.api;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import tw.exam.holdings.portfolio.Position;

/** App 1.0 API 的持股物件。欄位名稱、型別照規格；取不到收盤價時 current_price、price_date、pnl、return_pct 是 null（要輸出，不能省略）。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"id", "code", "name", "market", "lots", "buy_price", "current_price", "price_date", "pnl", "return_pct"})
public record HoldingDto(
        long id,
        String code,
        String name,
        String market,
        int lots,
        @JsonProperty("buy_price") BigDecimal buyPrice,
        @JsonProperty("current_price") BigDecimal currentPrice,
        @JsonProperty("price_date") String priceDate,
        Long pnl,
        @JsonProperty("return_pct") BigDecimal returnPct) {

    public static HoldingDto of(Position p) {
        var h = p.holding();
        var q = p.quote();
        var r = p.pnl();
        return new HoldingDto(
                h.id(),
                h.code(),
                p.name(),
                h.market() == null ? null : h.market().name(),
                h.lots(),
                h.buyPrice(),
                q == null ? null : q.close(),
                q == null ? null : q.date().toString(),
                r == null ? null : r.pnl(),
                r == null ? null : r.returnPct());
    }
}
