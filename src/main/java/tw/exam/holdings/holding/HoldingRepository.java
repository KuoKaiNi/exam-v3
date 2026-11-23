package tw.exam.holdings.holding;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import tw.exam.holdings.quote.Market;

@Repository
public class HoldingRepository {

    private static final String COLUMNS = "id, code, name, market, lots, buy_price";

    // 自己寫對應：用 JdbcClient 預設的屬性對應會先找 buyPrice 欄位，H2 每列都記一筆例外到 trace 檔
    private static final RowMapper<Holding> MAPPER = (rs, i) -> new Holding(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"),
            rs.getString("market") == null ? null : Market.valueOf(rs.getString("market")),
            rs.getInt("lots"), rs.getBigDecimal("buy_price"));

    private final JdbcClient jdbc;
    private final Clock clock;

    public HoldingRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<Holding> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM holding ORDER BY code")
                .query(MAPPER).list();
    }

    public Optional<Holding> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM holding WHERE id = ?")
                .param(id).query(MAPPER).optional();
    }

    public boolean existsByCode(String code) {
        return jdbc.sql("SELECT COUNT(*) FROM holding WHERE code = ?")
                .param(code).query(Integer.class).single() > 0;
    }

    /** code 重複時資料庫的唯一鍵會丟 DuplicateKeyException。 */
    public long insert(String code, String name, Market market, int lots, BigDecimal buyPrice) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO holding (code, name, market, lots, buy_price, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(code, name, market.name(), lots, buyPrice, now, now)
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    public boolean update(long id, int lots, BigDecimal buyPrice) {
        return jdbc.sql("UPDATE holding SET lots = ?, buy_price = ?, updated_at = ? WHERE id = ?")
                .params(lots, buyPrice, OffsetDateTime.now(clock), id)
                .update() > 0;
    }

    public void updateNameMarket(long id, String name, Market market) {
        jdbc.sql("UPDATE holding SET name = ?, market = ? WHERE id = ?")
                .params(name, market.name(), id).update();
    }

    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM holding WHERE id = ?").param(id).update() > 0;
    }
}
