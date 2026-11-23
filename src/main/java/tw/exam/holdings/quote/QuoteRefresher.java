package tw.exam.holdings.quote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tw.exam.holdings.holding.Holding;
import tw.exam.holdings.holding.HoldingService;

/**
 * 背景定時檢查每一筆持股的收盤價夠不夠新。
 * 每 {@code quote.refresh-check}（預設 5 分鐘）看一次；過時的才查，且同一檔至少隔 {@code quote.stale-retry}（預設 20 分鐘）。
 * 所以官方公布當天收盤價後，最晚約 25 分鐘清單上就看得到，不用重開伺服器。
 */
@Component
@ConditionalOnProperty(prefix = "quote", name = "refresh-enabled", havingValue = "true", matchIfMissing = true)
public class QuoteRefresher {

    private static final Logger log = LoggerFactory.getLogger(QuoteRefresher.class);

    private final HoldingService holdings;
    private final QuoteService quotes;

    public QuoteRefresher(HoldingService holdings, QuoteService quotes) {
        this.holdings = holdings;
        this.quotes = quotes;
    }

    @Scheduled(initialDelayString = "PT3S", fixedDelayString = "${quote.refresh-check}")
    public void refresh() {
        for (Holding h : holdings.list()) {
            try {
                if (h.market() == null) {
                    // 第 3 題以前建的資料沒有市場別，補查一次
                    holdings.resolveMarket(h);
                } else {
                    quotes.refreshIfStale(h.code(), h.market());
                }
            } catch (RuntimeException e) {
                log.warn("更新 {} 收盤價失敗：{}", h.code(), e.toString());
            }
        }
    }
}
