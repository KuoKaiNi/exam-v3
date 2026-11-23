package tw.exam.holdings.dividend;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tw.exam.holdings.ledger.ExRight;
import tw.exam.holdings.quote.Market;

@Repository
public class ExRightStore {

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public ExRightStore(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    public List<ExRight> findByCode(String source, String code) {
        return jdbc.sql("SELECT code, ex_date, kind, cash_per_share, stock_per_thousand FROM ex_right"
                        + " WHERE source = ? AND code = ? ORDER BY ex_date")
                .params(source, code)
                .query((rs, i) -> new ExRight(rs.getString("code"), rs.getObject("ex_date", LocalDate.class),
                        rs.getString("kind"), rs.getBigDecimal("cash_per_share"), rs.getBigDecimal("stock_per_thousand")))
                .list();
    }

    /** 明細還沒拿到的（證交所「權」「權息」）。 */
    public List<ExRight> findUnknown(String source, String code) {
        return findByCode(source, code).stream().filter(e -> !e.known()).toList();
    }

    /** 寫入一整年的資料。已經有明細的不會被「沒有明細」的蓋掉。 */
    @Transactional
    public void saveYear(String source, Market market, int year, List<ExRight> rows, Instant now) {
        OffsetDateTime at = now.atOffset(ZoneOffset.UTC);
        Map<String, ExRight> existing = jdbc.sql("SELECT code, ex_date, kind, cash_per_share, stock_per_thousand"
                        + " FROM ex_right WHERE source = ? AND market = ? AND YEAR(ex_date) = ?")
                .params(source, market.name(), year)
                .query((rs, i) -> new ExRight(rs.getString("code"), rs.getObject("ex_date", LocalDate.class),
                        rs.getString("kind"), rs.getBigDecimal("cash_per_share"), rs.getBigDecimal("stock_per_thousand")))
                .list().stream().collect(Collectors.toMap(e -> e.code() + "@" + e.exDate(), e -> e, (a, b) -> a));
        List<Object[]> inserts = new java.util.ArrayList<>();
        List<Object[]> updates = new java.util.ArrayList<>();
        for (ExRight r : rows) {
            ExRight old = existing.get(r.code() + "@" + r.exDate());
            if (old == null) {
                inserts.add(new Object[] {source, market.name(), r.code(), r.exDate(), r.kind(), r.cashPerShare(),
                        r.stockPerThousand(), at});
            } else if (r.known() && !r.equals(old)) {
                updates.add(new Object[] {r.kind(), r.cashPerShare(), r.stockPerThousand(), at, source, r.code(), r.exDate()});
            }
        }
        template.batchUpdate("INSERT INTO ex_right (source, market, code, ex_date, kind, cash_per_share,"
                + " stock_per_thousand, fetched_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", inserts);
        template.batchUpdate("UPDATE ex_right SET kind = ?, cash_per_share = ?, stock_per_thousand = ?, fetched_at = ?"
                + " WHERE source = ? AND code = ? AND ex_date = ?", updates);
        int n = jdbc.sql("UPDATE ex_right_fetch SET fetched_at = ? WHERE source = ? AND market = ? AND fetch_year = ?")
                .params(at, source, market.name(), year).update();
        if (n == 0) {
            jdbc.sql("INSERT INTO ex_right_fetch (source, market, fetch_year, fetched_at) VALUES (?, ?, ?, ?)")
                    .params(source, market.name(), year, at).update();
        }
    }

    public void saveDetail(String source, String code, LocalDate exDate, BigDecimal cash, BigDecimal stock, Instant now) {
        jdbc.sql("UPDATE ex_right SET cash_per_share = ?, stock_per_thousand = ?, fetched_at = ?"
                        + " WHERE source = ? AND code = ? AND ex_date = ?")
                .params(cash, stock, now.atOffset(ZoneOffset.UTC), source, code, exDate).update();
    }

    /** 某市場某年最後一次抓的時間。 */
    public Optional<Instant> fetchedAt(String source, Market market, int year) {
        return jdbc.sql("SELECT fetched_at FROM ex_right_fetch WHERE source = ? AND market = ? AND fetch_year = ?")
                .params(source, market.name(), year)
                .query((rs, i) -> rs.getObject("fetched_at", OffsetDateTime.class).toInstant())
                .optional();
    }
}
