package tw.exam.holdings.holding;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import tw.exam.holdings.AppException;

@Service
public class HoldingService {

    private final HoldingRepository repository;

    public HoldingService(HoldingRepository repository) {
        this.repository = repository;
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
        try {
            long id = repository.insert(code, lots, buyPrice);
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
        if (!repository.delete(id)) {
            throw AppException.notFound(id);
        }
    }
}
