package tw.exam.holdings.quote;

import java.time.Instant;
import java.util.List;

/** 收盤價快取的保存處。 */
public interface QuoteStore {

    record Saved(Quote quote, Instant fetchedAt) {
    }

    /** 只回傳從 source 主機查到的資料。 */
    List<Saved> load(String twseSource, String tpexSource);

    void save(Quote quote, String source, Instant fetchedAt);

    void delete(String code);

    /** 不保存（單元測試用）。 */
    QuoteStore NONE = new QuoteStore() {
        @Override
        public List<Saved> load(String twseSource, String tpexSource) {
            return List.of();
        }

        @Override
        public void save(Quote quote, String source, Instant fetchedAt) {
        }

        @Override
        public void delete(String code) {
        }
    };
}
