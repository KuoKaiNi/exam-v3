package tw.exam.holdings.holding;

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

    private static final String COLUMNS = "id, code, name, market";

    private static final RowMapper<Holding> MAPPER = (rs, i) -> new Holding(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"),
            rs.getString("market") == null ? null : Market.valueOf(rs.getString("market")));

    private final JdbcClient jdbc;
    private final Clock clock;

    public HoldingRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<Holding> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM holding ORDER BY code").query(MAPPER).list();
    }

    public Optional<Holding> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM holding WHERE id = ?").param(id).query(MAPPER).optional();
    }

    public Optional<Holding> findByCode(String code) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM holding WHERE code = ?").param(code).query(MAPPER).optional();
    }

    /** 在交易裡鎖住這一檔，同一檔的修改排隊做（手機和電腦同時改時不會算錯股數）。 */
    public boolean lock(long id) {
        return jdbc.sql("SELECT id FROM holding WHERE id = ? FOR UPDATE").param(id).query(Long.class).optional().isPresent();
    }

    /** code 重複時丟 DuplicateKeyException。 */
    public long insert(String code, String name, Market market) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO holding (code, name, market, created_at, updated_at) VALUES (?, ?, ?, ?, ?)")
                .params(code, name, market.name(), now, now)
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    public void updateNameMarket(long id, String name, Market market) {
        jdbc.sql("UPDATE holding SET name = ?, market = ? WHERE id = ?").params(name, market.name(), id).update();
    }

    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM holding WHERE id = ?").param(id).update() > 0;
    }
}
