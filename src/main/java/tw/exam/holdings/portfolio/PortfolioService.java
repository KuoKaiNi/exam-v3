package tw.exam.holdings.portfolio;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import tw.exam.holdings.dividend.ExRightService;
import tw.exam.holdings.holding.Holding;
import tw.exam.holdings.holding.HoldingService;
import tw.exam.holdings.holding.TradeRepository;
import tw.exam.holdings.ledger.ExRight;
import tw.exam.holdings.ledger.Ledger;
import tw.exam.holdings.ledger.LedgerResult;
import tw.exam.holdings.ledger.OversellException;
import tw.exam.holdings.ledger.Trade;
import tw.exam.holdings.quote.QuoteService;

/** 清單畫面與 API 共用：交易＋官方除權息＋快取裡的收盤價。這裡不向官方查。 */
@Service
public class PortfolioService {

    private final HoldingService holdings;
    private final TradeRepository trades;
    private final QuoteService quotes;
    private final ExRightService exRights;
    private final Clock clock;

    public PortfolioService(HoldingService holdings, TradeRepository trades, QuoteService quotes,
                            ExRightService exRights, Clock clock) {
        this.holdings = holdings;
        this.trades = trades;
        this.quotes = quotes;
        this.exRights = exRights;
        this.clock = clock;
    }

    public List<Position> positions() {
        return holdings.list().stream().map(this::position).toList();
    }

    /** 還持有中的（App 1.0 只看得到這些）。 */
    public List<Position> openPositions() {
        return positions().stream().filter(Position::open).toList();
    }

    public Position position(Holding h) {
        List<Trade> list = trades.findByHolding(h.id());
        List<ExRight> events = h.market() == null ? List.of() : exRights.events(h.code(), h.market());
        LedgerResult ledger;
        try {
            ledger = Ledger.compute(h.code(), list, events, LocalDate.now(clock));
        } catch (OversellException e) {
            // 寫入時都檢查過，正常不會發生；萬一官方後來更正配股資料，先不算股利，避免整頁錯誤
            ledger = Ledger.compute(h.code(), list, List.of(), LocalDate.now(clock));
        }
        LocalDate first = list.isEmpty() ? null : list.get(0).date();
        boolean complete = ledger.dividendsKnown() && h.market() != null && exRights.covered(h.market(), first);
        return new Position(h, list, ledger, quotes.cached(h.code()).orElse(null), complete);
    }

    public Position position(long holdingId) {
        return position(holdings.get(holdingId));
    }

    public Summary summary() {
        return Summary.of(openPositions());
    }

    public SummaryV2 summaryV2() {
        return SummaryV2.of(positions());
    }
}
