package tw.exam.holdings.api;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import tw.exam.holdings.portfolio.Position;

/**
 * App 1.0 API 的持股物件。欄位名稱、型別照規格；取不到收盤價時 current_price、price_date、pnl、return_pct 是 null（要輸出，不能省略）。
 *
 * <p>2.0 起資料改成交易紀錄，對應方式：lots＝股數 ÷ 1000 捨去（零股看不到，但盈虧照實際股數算）；
 * buy_price＝剩餘成本（不含手續費）÷ 股數；pnl、return_pct＝未實現損益與報酬率（不含已實現與股利）。
 * 只有一筆買進的持股（1.0 建的都是）跟 1.0 完全一樣。
 */
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
        boolean priced = p.open() && q != null;
        return new HoldingDto(
                h.id(),
                h.code(),
                p.name(),
                h.market() == null ? null : h.market().name(),
                p.v1Lots(),
                p.ledger().avgPriceExFee(),
                q == null ? null : q.close(),
                q == null ? null : q.date().toString(),
                priced ? p.unrealized() : null,
                priced ? p.unrealizedPct() : null);
    }
}
