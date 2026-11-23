package tw.exam.holdings.holding;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import tw.exam.holdings.AppException;
import tw.exam.holdings.quote.QuoteService;
import tw.exam.holdings.quote.QuoteService.Lookup;

@Service
public class HoldingService {

    private final HoldingRepository repository;
    private final QuoteService quotes;

    public HoldingService(HoldingRepository repository, QuoteService quotes) {
        this.repository = repository;
        this.quotes = quotes;
    }

    public List<Holding> list() {
        return repository.findAll();
    }

    public Holding get(long id) {
        return repository.findById(id).orElseThrow(() -> AppException.notFound(id));
    }

    /** 參數已經過 {@link HoldingInput} 檢查。 */
    public Holding create(String code, int lots, BigDecimal buyPrice) {
        if (repository.existsByCode(code)) {
            throw AppException.duplicate(code);
        }
        Lookup lookup = quotes.lookup(code);
        if (lookup instanceof Lookup.NotFound) {
            throw new AppException(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND",
                    "查不到股票代號 " + code + "，上市、上櫃都沒有這檔");
        }
        if (lookup instanceof Lookup.Unavailable u) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "QUOTE_UNAVAILABLE",
                    "官方行情網站暫時查不到，無法確認 " + code + " 是否存在，請稍後再試");
        }
        Lookup.Found found = (Lookup.Found) lookup;
        try {
            long id = repository.insert(code, found.name(), found.market(), lots, buyPrice);
            return get(id);
        } catch (DuplicateKeyException e) {
            // 兩個請求同時新增同一代號：由資料庫唯一鍵擋下
            throw AppException.duplicate(code);
        }
    }

    public Holding update(long id, int lots, BigDecimal buyPrice) {
        if (!repository.update(id, lots, buyPrice)) {
            throw AppException.notFound(id);
        }
        return get(id);
    }

    public void delete(long id) {
        Holding h = get(id);
        if (!repository.delete(id)) {
            throw AppException.notFound(id);
        }
        quotes.evict(h.code());
    }

    /** 補上第 3 題之前建的資料缺的名稱與市場別。查不到就留著下次再補。 */
    public void resolveMarket(Holding h) {
        if (quotes.lookup(h.code()) instanceof Lookup.Found f) {
            repository.updateNameMarket(h.id(), f.name(), f.market());
        }
    }
}
