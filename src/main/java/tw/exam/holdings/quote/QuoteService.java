package tw.exam.holdings.quote;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

/**
 * 收盤價的快取（記憶體＋資料庫 quote_cache）。打開清單只讀記憶體，不向官方查；向官方查只有兩個時機：
 * <ol>
 *   <li>新增持股時查代號（同時判斷上市或上櫃）；</li>
 *   <li>背景定時檢查：手上的收盤價比「應該已公布的最近交易日」舊，才重查。</li>
 * </ol>
 */
@Service
public class QuoteService {

    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);

    /** 新增持股時查代號的結果。 */
    public sealed interface Lookup {
        record Found(Market market, String name, Quote quote) implements Lookup {
        }

        record NotFound() implements Lookup {
        }

        record Unavailable(String reason) implements Lookup {
        }
    }

    private record Entry(Quote quote, Instant lastAttempt) {
    }

    private final OfficialQuoteClient client;
    private final QuoteProperties props;
    private final Clock clock;
    private final QuoteStore store;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final ExecutorService lookupPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "quote-lookup");
        t.setDaemon(true);
        return t;
    });

    public QuoteService(OfficialQuoteClient client, QuoteProperties props, Clock clock, QuoteStore store) {
        this.client = client;
        this.props = props;
        this.clock = clock;
        this.store = store;
        // 重啟後先用上次存下來的收盤價，不必等重查官方；之後照一般規則判斷要不要更新
        for (QuoteStore.Saved saved : store.load(props.baseUrl(Market.TWSE), props.baseUrl(Market.TPEX))) {
            cache.put(saved.quote().code(), new Entry(saved.quote(), saved.fetchedAt()));
        }
        log.info("載入 {} 筆存下來的收盤價", cache.size());
    }

    @PreDestroy
    void shutdown() {
        lookupPool.shutdownNow();
    }

    /** 清單用：只看快取。沒有就是「未取得」。 */
    public Optional<Quote> cached(String code) {
        Entry e = cache.get(code);
        return e == null ? Optional.empty() : Optional.ofNullable(e.quote());
    }

    /**
     * 新增持股時查代號。上市、上櫃同時查；兩邊都正常回應「沒有」才算代號不存在，
     * 有一邊連不上就不能下結論（回 Unavailable，不儲存）。
     */
    public Lookup lookup(String code) {
        Instant deadline = clock.instant().plus(props.lookupTimeout());
        CompletableFuture<MonthData> twse = CompletableFuture.supplyAsync(() -> recent(Market.TWSE, code, deadline), lookupPool);
        CompletableFuture<MonthData> tpex = CompletableFuture.supplyAsync(() -> recent(Market.TPEX, code, deadline), lookupPool);
        // 哪一邊先找到就先回，不用等另一邊查完（上櫃股不必等證交所排隊）。
        // 寫快取只由「真的完成結果」的那一方做一次，免得後到的回呼把查詢時間蓋掉。
        CompletableFuture<Lookup> result = new CompletableFuture<>();
        twse.thenAccept(d -> {
            if (d.status() == MonthData.Status.FOUND) {
                completeOnce(result, code, found(Market.TWSE, code, d));
            }
        });
        tpex.thenAccept(d -> {
            if (d.status() == MonthData.Status.FOUND) {
                completeOnce(result, code, found(Market.TPEX, code, d));
            }
        });
        // 注意：這個回呼可能比上面的 thenAccept 先跑（CompletableFuture 後註冊的先執行），
        // 所以這裡也要先看有沒有哪一邊找到，不能假設兩邊都沒找到。
        CompletableFuture.allOf(twse, tpex).whenComplete((v, ex) -> completeOnce(result, code,
                ex != null ? new Lookup.Unavailable(ex.toString()) : conclude(code, twse.join(), tpex.join())));
        try {
            return result.get(props.lookupTimeout().toMillis() + 500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Lookup.Unavailable("查詢被中斷");
        } catch (Exception e) {
            log.warn("查代號 {} 逾時或失敗：{}", code, e.toString());
            return new Lookup.Unavailable("查詢逾時");
        }
    }

    /** 兩邊都查完：有找到就回找到；都正常回「沒有」才算代號不存在；其他情況是查不到。 */
    private static Lookup conclude(String code, MonthData twse, MonthData tpex) {
        if (twse.status() == MonthData.Status.FOUND) {
            return found(Market.TWSE, code, twse);
        }
        if (tpex.status() == MonthData.Status.FOUND) {
            return found(Market.TPEX, code, tpex);
        }
        if (twse.status() == MonthData.Status.EMPTY && tpex.status() == MonthData.Status.EMPTY) {
            return new Lookup.NotFound();
        }
        MonthData failed = twse.status() == MonthData.Status.FAILED ? twse : tpex;
        return new Lookup.Unavailable(failed.failure());
    }

    /** 背景更新用：手上的資料夠新就不查。 */
    public void refreshIfStale(String code, Market market) {
        if (!needsRefresh(code)) {
            return;
        }
        Instant deadline = clock.instant().plus(props.requestTimeout().multipliedBy(4));
        MonthData data = recent(market, code, deadline);
        Entry old = cache.get(code);
        Optional<Quote> fresh = toQuote(market, code, data);
        Quote quote = fresh.orElse(old == null ? null : old.quote());
        cache.put(code, new Entry(quote, clock.instant()));
        fresh.ifPresent(q -> save(q));
    }

    boolean needsRefresh(String code) {
        Entry e = cache.get(code);
        if (e == null) {
            return true;
        }
        Instant now = clock.instant();
        if (e.quote() == null) {
            return e.lastAttempt().plus(props.failedRetry()).isBefore(now);
        }
        ZonedDateTime taipei = ZonedDateTime.now(clock);
        if (!e.quote().date().isBefore(TradingCalendar.expectedLatest(taipei))) {
            return false;
        }
        return e.lastAttempt().plus(props.staleRetry()).isBefore(now);
    }

    public void evict(String code) {
        cache.remove(code);
        store.delete(code);
    }

    private void save(Quote q) {
        try {
            store.save(q, props.baseUrl(q.market()), clock.instant());
        } catch (RuntimeException e) {
            // 存不進資料庫不影響畫面，記憶體裡還有
            log.warn("收盤價 {} 存入資料庫失敗：{}", q.code(), e.toString());
        }
    }

    /** 查這個月；這個月還沒有交易日（例如月初第一天收盤前）就查上個月。 */
    private MonthData recent(Market market, String code, Instant deadline) {
        YearMonth thisMonth = YearMonth.now(clock);
        MonthData data = client.fetchMonth(market, code, thisMonth, deadline);
        if (data.status() == MonthData.Status.EMPTY
                || data.status() == MonthData.Status.FOUND && data.latest().isEmpty()) {
            MonthData previous = client.fetchMonth(market, code, thisMonth.minusMonths(1), deadline);
            if (previous.status() != MonthData.Status.EMPTY || data.status() == MonthData.Status.EMPTY) {
                return previous;
            }
        }
        return data;
    }

    /** 只組結果，不寫快取。 */
    private static Lookup found(Market market, String code, MonthData data) {
        return new Lookup.Found(market, data.name(), toQuote(market, code, data).orElse(null));
    }

    /** 只有真的把結果放進 result 的那一方寫快取，所以只寫一次。 */
    private void completeOnce(CompletableFuture<Lookup> result, String code, Lookup lookup) {
        if (result.complete(lookup) && lookup instanceof Lookup.Found f) {
            cache.put(code, new Entry(f.quote(), clock.instant()));
            if (f.quote() != null) {
                save(f.quote());
            }
        }
    }

    private static Optional<Quote> toQuote(Market market, String code, MonthData data) {
        if (data.status() != MonthData.Status.FOUND) {
            return Optional.empty();
        }
        return data.latest().map(d -> new Quote(code, data.name(), market, d.date(), d.close()));
    }
}
