package tw.exam.holdings.api;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import tw.exam.holdings.ledger.DividendLine;
import tw.exam.holdings.ledger.Trade;
import tw.exam.holdings.portfolio.Position;
import tw.exam.holdings.portfolio.SummaryV2;

/** 2.0 API 的回應物件，欄位說明見 API.md。null 一律輸出，不省略。 */
public final class V2Dtos {

    private V2Dtos() {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TradeDto(
            long id,
            @JsonProperty("holding_id") long holdingId,
            String code,
            String side,
            @JsonProperty("trade_date") String tradeDate,
            long shares,
            BigDecimal price,
            int version,
            @JsonProperty("realized_pnl") Long realizedPnl) {

        static TradeDto of(Position p, Trade t) {
            return new TradeDto(t.id(), t.holdingId(), p.holding().code(), t.side().name(), t.date().toString(),
                    t.shares(), t.price(), t.version(), p.ledger().realizedBySell().get(t.id()));
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DividendDto(
            @JsonProperty("ex_date") String exDate,
            String kind,
            @JsonProperty("eligible_shares") long eligibleShares,
            @JsonProperty("cash_per_share") BigDecimal cashPerShare,
            @JsonProperty("stock_per_thousand") BigDecimal stockPerThousand,
            Long cash,
            @JsonProperty("stock_shares") Long stockShares,
            @JsonProperty("nhi_premium_estimate") Long nhiPremiumEstimate) {

        static DividendDto of(DividendLine d) {
            return new DividendDto(d.exDate().toString(), d.kind(), d.eligibleShares(), plain(d.cashPerShare()),
                    plain(d.stockPerThousand()), d.cash(), d.stockShares(), d.known() ? d.nhiPremiumEstimate() : null);
        }

        /** 資料庫存 8 位小數，輸出時去掉多餘的 0（1.20000000 → 1.2，0E-8 → 0）。 */
        private static BigDecimal plain(BigDecimal v) {
            if (v == null) {
                return null;
            }
            BigDecimal s = v.stripTrailingZeros();
            return s.scale() < 0 ? s.setScale(0) : s;
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record HoldingV2Dto(
            long id,
            String code,
            String name,
            String market,
            long shares,
            @JsonProperty("avg_cost") BigDecimal avgCost,
            @JsonProperty("total_cost") long totalCost,
            @JsonProperty("current_price") BigDecimal currentPrice,
            @JsonProperty("price_date") String priceDate,
            @JsonProperty("market_value") Long marketValue,
            @JsonProperty("unrealized_pnl") Long unrealizedPnl,
            @JsonProperty("unrealized_pct") BigDecimal unrealizedPct,
            @JsonProperty("realized_pnl") long realizedPnl,
            @JsonProperty("cash_dividends") long cashDividends,
            @JsonProperty("stock_dividend_shares") long stockDividendShares,
            @JsonProperty("total_return") Long totalReturn,
            @JsonProperty("dividend_data_complete") boolean dividendDataComplete,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<TradeDto> trades,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<DividendDto> dividends) {

        static HoldingV2Dto of(Position p, boolean detail) {
            var q = p.quote();
            return new HoldingV2Dto(p.holding().id(), p.holding().code(), p.name(),
                    p.holding().market() == null ? null : p.holding().market().name(),
                    p.shares(), p.ledger().avgCost(), p.ledger().totalCost(),
                    q == null ? null : q.close(), q == null ? null : q.date().toString(),
                    p.marketValue(), p.unrealized(), p.unrealizedPct(),
                    p.ledger().realizedPnl(), p.ledger().cashDividends(), p.ledger().stockDividendShares(),
                    p.totalReturn(), p.dividendDataComplete(),
                    detail ? p.trades().stream().map(t -> TradeDto.of(p, t)).toList() : null,
                    detail ? p.ledger().dividends().stream().map(DividendDto::of).toList() : null);
        }
    }

    public record SummaryV2Dto(
            @JsonProperty("total_cost") long totalCost,
            @JsonProperty("market_value") long marketValue,
            @JsonProperty("unrealized_pnl") long unrealizedPnl,
            @JsonProperty("realized_pnl") long realizedPnl,
            @JsonProperty("cash_dividends") long cashDividends,
            @JsonProperty("total_return") long totalReturn,
            @JsonProperty("price_missing") int priceMissing,
            @JsonProperty("dividend_data_incomplete") int dividendDataIncomplete) {

        static SummaryV2Dto of(SummaryV2 s) {
            return new SummaryV2Dto(s.totalCost(), s.marketValue(), s.unrealizedPnl(), s.realizedPnl(),
                    s.cashDividends(), s.totalReturn(), s.priceMissing(), s.dividendDataIncomplete());
        }
    }

    /** 新增、修改交易的回應：交易本身＋這檔股票算完的結果。 */
    public record TradeResultDto(TradeDto trade, HoldingV2Dto holding) {
    }
}
