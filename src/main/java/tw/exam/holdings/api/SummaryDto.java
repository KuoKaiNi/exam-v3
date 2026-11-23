package tw.exam.holdings.api;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import tw.exam.holdings.portfolio.Summary;

@JsonPropertyOrder({"total_cost", "market_value", "pnl", "return_pct", "quote_missing"})
public record SummaryDto(
        @JsonProperty("total_cost") long totalCost,
        @JsonProperty("market_value") long marketValue,
        long pnl,
        @JsonProperty("return_pct") BigDecimal returnPct,
        @JsonProperty("quote_missing") int quoteMissing) {

    public static SummaryDto of(Summary s) {
        return new SummaryDto(s.totalCost(), s.marketValue(), s.pnl(), s.returnPct(), s.quoteMissing());
    }
}
