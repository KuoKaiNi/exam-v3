package tw.exam.holdings.holding;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import tw.exam.holdings.AppException;
import tw.exam.holdings.dividend.ExRightService;
import tw.exam.holdings.ledger.Ledger;
import tw.exam.holdings.ledger.LedgerResult;
import tw.exam.holdings.ledger.OversellException;
import tw.exam.holdings.ledger.Side;
import tw.exam.holdings.ledger.Trade;
import tw.exam.holdings.quote.QuoteService;

/**
 * 交易的新增、修改、刪除。每次都在資料庫交易裡先鎖住那一檔，再用改完後的整條時間軸檢查會不會賣超，
 * 所以手機和電腦同時操作同一檔也不會讓股數變成負的。修改、刪除要帶 version，被別台裝置先改過就回 409。
 */
@Service
public class TradeService {

    private final HoldingService holdings;
    private final HoldingRepository holdingRepo;
    private final TradeRepository trades;
    private final ExRightService exRights;
    private final QuoteService quotes;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TradeService(HoldingService holdings, HoldingRepository holdingRepo, TradeRepository trades,
                        ExRightService exRights, QuoteService quotes, TransactionTemplate tx, Clock clock) {
        this.holdings = holdings;
        this.holdingRepo = holdingRepo;
        this.trades = trades;
        this.exRights = exRights;
        this.quotes = quotes;
        this.tx = tx;
        this.clock = clock;
    }

    public Trade get(long id) {
        return trades.findById(id).orElseThrow(() -> tradeNotFound(id));
    }

    public List<Trade> byHolding(long holdingId) {
        return trades.findByHolding(holdingId);
    }

    /** 新增一筆交易。第一次記的代號會先向官方查（不在資料庫交易裡）。 */
    public Trade add(String code, Side side, LocalDate date, long shares, BigDecimal price) {
        Holding h;
        if (side == Side.SELL) {
            h = holdings.findByCode(code).orElseThrow(() -> new AppException(HttpStatus.CONFLICT,
                    "INSUFFICIENT_SHARES", "沒有 " + code + " 的持股，不能賣出"));
        } else {
            h = holdings.getOrCreate(code);
        }
        Trade saved = tx.execute(s -> {
            lock(h);
            List<Trade> list = new ArrayList<>(trades.findByHolding(h.id()));
            list.add(new Trade(Long.MAX_VALUE, h.id(), side, date, shares, price, 1));
            check(h, list);
            return get(trades.insert(h.id(), side, date, shares, price));
        });
        exRights.requestSync();
        return saved;
    }

    public Trade update(long id, int version, Side side, LocalDate date, long shares, BigDecimal price) {
        Trade before = get(id);
        Holding h = holdings.get(before.holdingId());
        Trade saved = tx.execute(s -> {
            lock(h);
            Trade current = get(id);
            if (current.version() != version) {
                throw versionConflict(current);
            }
            Trade changed = new Trade(id, h.id(), side, date, shares, price, version);
            check(h, replace(trades.findByHolding(h.id()), id, t -> changed));
            trades.update(id, version, side, date, shares, price);
            return get(id);
        });
        exRights.requestSync();
        return saved;
    }

    /** 刪掉最後一筆交易時，這檔股票也一起刪掉。 */
    public void delete(long id, int version) {
        Trade before = get(id);
        Holding h = holdings.get(before.holdingId());
        boolean holdingGone = Boolean.TRUE.equals(tx.execute(s -> {
            lock(h);
            Trade current = get(id);
            if (current.version() != version) {
                throw versionConflict(current);
            }
            List<Trade> rest = replace(trades.findByHolding(h.id()), id, t -> null);
            check(h, rest);
            trades.delete(id, version);
            if (rest.isEmpty()) {
                holdingRepo.delete(h.id());
                return true;
            }
            return false;
        }));
        if (holdingGone) {
            quotes.evict(h.code());
        }
    }

    // ---- App 1.0 的操作（規格不能改）----

    /** 1.0 新增持股：記成今天（台北時間）的一筆買進。還持有中的代號回 409；已經全部賣掉的可以再買。 */
    public Holding v1Create(String code, int lots, BigDecimal price) {
        holdings.findByCode(code).ifPresent(h -> {
            if (check(h, trades.findByHolding(h.id())).shares() > 0) {
                throw AppException.duplicate(code);
            }
        });
        Trade t = add(code, Side.BUY, LocalDate.now(clock), lots * 1000L, price);
        return holdings.get(t.holdingId());
    }

    /**
     * 1.0 修改張數、買進價：只有「只有一筆買進」的持股能這樣改（1.0 建的都是）。
     * 有多筆交易的，1.0 的「張數＋買進價」對應不回去，回 400 請使用者改用網頁或 2.0 App。
     */
    public Holding v1Update(long id, int lots, BigDecimal price) {
        Holding h = holdings.get(id);
        List<Trade> list = trades.findByHolding(id);
        if (list.size() != 1 || list.get(0).side() != Side.BUY) {
            throw AppException.validation(h.code() + " 有 " + list.size() + " 筆交易紀錄，1.0 版不能修改，請改用網頁或 2.0 版 App");
        }
        Trade t = list.get(0);
        update(t.id(), t.version(), Side.BUY, t.date(), lots * 1000L, price);
        return h;
    }

    public void v1Delete(long id) {
        holdings.delete(id);
    }

    private void lock(Holding h) {
        if (!holdingRepo.lock(h.id())) {
            throw AppException.notFound(h.id());
        }
    }

    /** 用改完後的整條時間軸檢查：任何一天賣超都不行。 */
    private LedgerResult check(Holding h, List<Trade> list) {
        try {
            return Ledger.compute(h.code(), list, h.market() == null ? List.of() : exRights.events(h.code(), h.market()),
                    LocalDate.now(clock));
        } catch (OversellException e) {
            throw new AppException(HttpStatus.CONFLICT, "INSUFFICIENT_SHARES",
                    h.code() + " 在 " + e.date() + " 賣出 " + e.requested() + " 股，但當時只持有 " + e.available() + " 股");
        }
    }

    private static List<Trade> replace(List<Trade> list, long id, UnaryOperator<Trade> f) {
        List<Trade> out = new ArrayList<>();
        for (Trade t : list) {
            Trade r = t.id() == id ? f.apply(t) : t;
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    private static AppException tradeNotFound(long id) {
        return new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "找不到這筆交易（id " + id + "）");
    }

    private static AppException versionConflict(Trade current) {
        return new AppException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                "這筆交易已經在別的裝置上改過（目前版本 " + current.version() + "），請重新整理後再改");
    }
}
