package tw.exam.holdings.holding;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import tw.exam.holdings.ledger.Side;
import tw.exam.holdings.ledger.Trade;

@Repository
public class TradeRepository {

    private static final String COLUMNS = "id, holding_id, side, trade_date, shares, price, version";

    private static final RowMapper<Trade> MAPPER = (rs, i) -> new Trade(
            rs.getLong("id"), rs.getLong("holding_id"), Side.valueOf(rs.getString("side")),
            rs.getObject("trade_date", LocalDate.class), rs.getLong("shares"), rs.getBigDecimal("price"),
            rs.getInt("version"));

    private final JdbcClient jdbc;
    private final Clock clock;

    public TradeRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<Trade> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM trade ORDER BY trade_date, id").query(MAPPER).list();
    }

    public List<Trade> findByHolding(long holdingId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM trade WHERE holding_id = ? ORDER BY trade_date, id")
                .param(holdingId).query(MAPPER).list();
    }

    public Optional<Trade> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM trade WHERE id = ?").param(id).query(MAPPER).optional();
    }

    public Optional<LocalDate> earliestDate() {
        return jdbc.sql("SELECT MIN(trade_date) FROM trade").query(LocalDate.class).optional();
    }

    public long insert(long holdingId, Side side, LocalDate date, long shares, BigDecimal price) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO trade (holding_id, side, trade_date, shares, price, version, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 1, ?, ?)")
                .params(holdingId, side.name(), date, shares, price, now, now)
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    /** 版本對得上才改，回傳有沒有改到。 */
    public boolean update(long id, int version, Side side, LocalDate date, long shares, BigDecimal price) {
        return jdbc.sql("UPDATE trade SET side = ?, trade_date = ?, shares = ?, price = ?, version = version + 1,"
                        + " updated_at = ? WHERE id = ? AND version = ?")
                .params(side.name(), date, shares, price, OffsetDateTime.now(clock), id, version)
                .update() > 0;
    }

    public boolean delete(long id, int version) {
        return jdbc.sql("DELETE FROM trade WHERE id = ? AND version = ?").params(id, version).update() > 0;
    }

    public int countByHolding(long holdingId) {
        return jdbc.sql("SELECT COUNT(*) FROM trade WHERE holding_id = ?").param(holdingId).query(Integer.class).single();
    }
}
