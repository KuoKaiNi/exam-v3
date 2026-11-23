package tw.exam.holdings.portfolio;

import java.util.List;

import org.springframework.stereotype.Service;

import tw.exam.holdings.holding.Holding;
import tw.exam.holdings.holding.HoldingService;
import tw.exam.holdings.quote.QuoteService;

/** 清單畫面與 API 共用：持股＋快取裡的收盤價。這裡不向官方查。 */
@Service
public class PortfolioService {

    private final HoldingService holdings;
    private final QuoteService quotes;

    public PortfolioService(HoldingService holdings, QuoteService quotes) {
        this.holdings = holdings;
        this.quotes = quotes;
    }

    public List<Position> positions() {
        return holdings.list().stream().map(this::position).toList();
    }

    public Position position(Holding h) {
        return new Position(h, quotes.cached(h.code()).orElse(null));
    }
}
