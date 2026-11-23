package tw.exam.holdings.dividend;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import tw.exam.holdings.holding.Holding;
import tw.exam.holdings.holding.HoldingRepository;
import tw.exam.holdings.holding.TradeRepository;
import tw.exam.holdings.ledger.ExRight;
import tw.exam.holdings.quote.Market;
import tw.exam.holdings.quote.OfficialQuoteClient;
import tw.exam.holdings.quote.OfficialQuoteClient.Fetched;
import tw.exam.holdings.quote.QuoteProperties;

/**
 * 官方除權息資料：按年抓整個市場的「除權除息計算結果表」存進資料庫，算股利時只讀資料庫。
 *
 * <ul>
 *   <li>要抓的年份：最早一筆交易的年份到今年。使用者補登更早的交易時會自動補抓。</li>
 *   <li>過去的年份抓一次就好（如果是在那年還沒結束時抓的，跨年後再抓一次補齊）；今年每 {@code dividend.current-year-refresh} 重抓。</li>
 *   <li>證交所「權」「權息」只有合計值，持有的股票才另外查明細 TWT49UDetail。</li>
 * </ul>
 */
@Service
public class ExRightService {

    private static final Logger log = LoggerFactory.getLogger(ExRightService.class);
    static final Duration YEAR_TABLE_TIMEOUT = Duration.ofSeconds(30);

    private final OfficialQuoteClient client;
    private final QuoteProperties props;
    private final ExRightStore store;
    private final HoldingRepository holdings;
    private final TradeRepository trades;
    private final Clock clock;
    private final Duration currentYearRefresh;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ex-right-sync");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean pending = new AtomicBoolean();

    public ExRightService(OfficialQuoteClient client, QuoteProperties props, ExRightStore store,
                          HoldingRepository holdings, TradeRepository trades, Clock clock,
                          @Value("${dividend.current-year-refresh:3h}") Duration currentYearRefresh) {
        this.client = client;
        this.props = props;
        this.store = store;
        this.holdings = holdings;
        this.trades = trades;
        this.clock = clock;
        this.currentYearRefresh = currentYearRefresh;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    public List<ExRight> events(String code, Market market) {
        return store.findByCode(props.baseUrl(market), code);
    }

    /** 從 from 那年到今年的表是不是都抓過了。 */
    public boolean covered(Market market, LocalDate from) {
        if (from == null) {
            return true;
        }
        String source = props.baseUrl(market);
        for (int y = from.getYear(); y <= today().getYear(); y++) {
            if (store.fetchedAt(source, market, y).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 交易有變動時叫一下，背景補抓缺的年份與明細（不會讓請求等）。 */
    public void requestSync() {
        if (!props.refreshEnabled()) {
            return;
        }
        if (pending.compareAndSet(false, true)) {
            worker.submit(() -> {
                pending.set(false);
                try {
                    sync();
                } catch (RuntimeException e) {
                    log.warn("同步除權息資料失敗：{}", e.toString());
                }
            });
        }
    }

    /** 背景同步；排程與 requestSync 都走這裡。 */
    public synchronized void sync() {
        Optional<LocalDate> earliest = trades.earliestDate();
        if (earliest.isEmpty()) {
            return;
        }
        List<Holding> all = holdings.findAll();
        for (Market market : Market.values()) {
            if (all.stream().noneMatch(h -> h.market() == market)) {
                continue;
            }
            for (int y = earliest.get().getYear(); y <= today().getYear(); y++) {
                syncYear(market, y);
            }
        }
        for (Holding h : all) {
            if (h.market() == Market.TWSE) {
                syncDetails(h.code());
            }
        }
    }

    void syncYear(Market market, int year) {
        String source = props.baseUrl(market);
        Optional<Instant> fetched = store.fetchedAt(source, market, year);
        LocalDate today = today();
        if (fetched.isPresent()) {
            if (year < today.getYear()) {
                LocalDate fetchedDay = fetched.get().atZone(zone()).toLocalDate();
                if (fetchedDay.getYear() > year) {
                    return;
                }
            } else if (fetched.get().plus(currentYearRefresh).isAfter(clock.instant())) {
                return;
            }
        }
        LocalDate end = year < today.getYear() ? LocalDate.of(year, 12, 31) : today;
        String path = market == Market.TWSE
                ? String.format("/rwd/zh/exRight/TWT49U?startDate=%04d0101&endDate=%04d%02d%02d&response=json",
                        year, end.getYear(), end.getMonthValue(), end.getDayOfMonth())
                : String.format("/www/zh-tw/bulletin/exDailyQ?startDate=%04d/01/01&endDate=%04d/%02d/%02d&response=json",
                        year, end.getYear(), end.getMonthValue(), end.getDayOfMonth());
        Fetched f = client.get(market, path, clock.instant().plus(YEAR_TABLE_TIMEOUT.multipliedBy(2)), YEAR_TABLE_TIMEOUT);
        if (!f.ok()) {
            log.warn("抓 {} {} 年除權息表失敗：{}", market, year, f.failure());
            return;
        }
        ExRightParser.Parsed parsed = market == Market.TWSE ? ExRightParser.parseTwse(f.body()) : ExRightParser.parseTpex(f.body());
        if (!parsed.ok()) {
            log.warn("{} {} 年除權息表看不懂", market, year);
            return;
        }
        store.saveYear(source, market, year, parsed.rows(), clock.instant());
        log.info("抓 {} {} 年除權息表：{} 筆", market, year, parsed.rows().size());
    }

    void syncDetails(String code) {
        String source = props.baseUrl(Market.TWSE);
        for (ExRight e : store.findUnknown(source, code)) {
            if (e.exDate().isAfter(today())) {
                continue;
            }
            String path = String.format("/rwd/zh/exRight/TWT49UDetail?STK_NO=%s&T1=%04d%02d%02d&response=json",
                    code, e.exDate().getYear(), e.exDate().getMonthValue(), e.exDate().getDayOfMonth());
            Fetched f = client.get(Market.TWSE, path, clock.instant().plus(props.requestTimeout().multipliedBy(3)));
            if (!f.ok()) {
                log.warn("查 {} {} 除權息明細失敗：{}", code, e.exDate(), f.failure());
                continue;
            }
            Optional<BigDecimal[]> d = ExRightParser.parseTwseDetail(f.body());
            if (d.isPresent()) {
                store.saveDetail(source, code, e.exDate(), d.get()[0], d.get()[1], clock.instant());
                log.info("查 {} {} 除權息明細：現金 {}、每千股配 {}", code, e.exDate(), d.get()[0], d.get()[1]);
            } else {
                log.warn("{} {} 除權息明細查無資料", code, e.exDate());
            }
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private ZoneId zone() {
        return clock.getZone();
    }
}
