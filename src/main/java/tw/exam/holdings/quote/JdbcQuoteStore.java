package tw.exam.holdings.quote;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcQuoteStore implements QuoteStore {

    private final JdbcClient jdbc;

    public JdbcQuoteStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Saved> load(String twseSource, String tpexSource) {
        return jdbc.sql("SELECT code, name, market, price_date, close_price, fetched_at FROM quote_cache"
                        + " WHERE (market = 'TWSE' AND source = ?) OR (market = 'TPEX' AND source = ?)")
                .params(twseSource, tpexSource)
                .query((rs, i) -> new Saved(
                        new Quote(rs.getString("code"), rs.getString("name"), Market.valueOf(rs.getString("market")),
                                rs.getDate("price_date").toLocalDate(), rs.getBigDecimal("close_price")),
                        rs.getObject("fetched_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    @Override
    @Transactional
    public void save(Quote q, String source, Instant fetchedAt) {
        OffsetDateTime at = fetchedAt.atOffset(ZoneOffset.UTC);
        int n = jdbc.sql("UPDATE quote_cache SET name = ?, market = ?, price_date = ?, close_price = ?, source = ?,"
                        + " fetched_at = ? WHERE code = ?")
                .params(q.name(), q.market().name(), q.date(), q.close(), source, at, q.code())
                .update();
        if (n == 0) {
            jdbc.sql("INSERT INTO quote_cache (code, name, market, price_date, close_price, source, fetched_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(q.code(), q.name(), q.market().name(), q.date(), q.close(), source, at)
                    .update();
        }
    }

    @Override
    public void delete(String code) {
        jdbc.sql("DELETE FROM quote_cache WHERE code = ?").param(code).update();
    }
}
