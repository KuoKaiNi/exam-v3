package tw.exam.holdings.holding;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import tw.exam.holdings.AppException;
import tw.exam.holdings.quote.QuoteService;
import tw.exam.holdings.quote.QuoteService.Lookup;

/** 股票本身（代號、名稱、市場別）。交易的增刪改在 TradeService。 */
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

    public Optional<Holding> findByCode(String code) {
        return repository.findByCode(code);
    }

    /** 第一次記這檔：向官方查代號，判斷上市或上櫃（最多約 8 秒）。不在資料庫交易裡做。 */
    public Holding getOrCreate(String code) {
        Optional<Holding> existing = repository.findByCode(code);
        if (existing.isPresent()) {
            return existing.get();
        }
        Lookup lookup = quotes.lookup(code);
        if (lookup instanceof Lookup.NotFound) {
            throw new AppException(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND",
                    "查不到股票代號 " + code + "，上市、上櫃都沒有這檔");
        }
        if (lookup instanceof Lookup.Unavailable) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "QUOTE_UNAVAILABLE",
                    "官方行情網站暫時查不到，無法確認 " + code + " 是否存在，請稍後再試");
        }
        Lookup.Found found = (Lookup.Found) lookup;
        try {
            return get(repository.insert(code, found.name(), found.market()));
        } catch (DuplicateKeyException e) {
            // 手機和電腦同時記第一筆
            return repository.findByCode(code).orElseThrow();
        }
    }

    public void delete(long id) {
        Holding h = get(id);
        repository.delete(id);
        quotes.evict(h.code());
    }

    /** 補上第 3 題之前建的資料缺的名稱與市場別。查不到就留著下次再補。 */
    public void resolveMarket(Holding h) {
        if (quotes.lookup(h.code()) instanceof Lookup.Found f) {
            repository.updateNameMarket(h.id(), f.name(), f.market());
        }
    }
}
