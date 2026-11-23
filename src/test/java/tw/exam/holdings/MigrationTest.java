package tw.exam.holdings;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 1.0 留下的資料庫（V1～V3）升到 2.0（V4）：每筆持股變成一筆買進，id 不變。 */
class MigrationTest {

    @Test
    void v1HoldingsBecomeBuyTrades() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:migration-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        Flyway.configure().dataSource(ds).target("3").load().migrate();
        JdbcClient jdbc = JdbcClient.create(ds);
        // UTC 2026-11-22 17:00 ＝ 台北 11-23 01:00：買進日要是 11-23
        jdbc.sql("INSERT INTO holding (code, name, market, lots, buy_price, created_at, updated_at)"
                + " VALUES ('2330', '台積電', 'TWSE', 2, 1000.50, TIMESTAMP WITH TIME ZONE '2026-11-22 17:00:00+00',"
                + " TIMESTAMP WITH TIME ZONE '2026-11-22 17:00:00+00')").update();
        jdbc.sql("INSERT INTO holding (code, name, market, lots, buy_price, created_at, updated_at)"
                + " VALUES ('6488', '環球晶', 'TPEX', 1, 500, TIMESTAMP WITH TIME ZONE '2026-11-20 10:00:00+08',"
                + " TIMESTAMP WITH TIME ZONE '2026-11-21 10:00:00+08')").update();

        // 1.0 用過一陣子：刪過一筆，id 不連號
        jdbc.sql("DELETE FROM holding WHERE code = '6488'").update();
        jdbc.sql("INSERT INTO holding (code, name, market, lots, buy_price, created_at, updated_at)"
                + " VALUES ('6488', '環球晶', 'TPEX', 1, 500, TIMESTAMP WITH TIME ZONE '2026-11-20 10:00:00+08',"
                + " TIMESTAMP WITH TIME ZONE '2026-11-21 10:00:00+08')").update();
        List<Long> idsBefore = jdbc.sql("SELECT id FROM holding ORDER BY id").query(Long.class).list();

        Flyway.configure().dataSource(ds).load().migrate();

        List<Map<String, Object>> rows = jdbc.sql("SELECT holding_id, side, trade_date, shares, price, version FROM trade ORDER BY holding_id")
                .query().listOfRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("HOLDING_ID", idsBefore.get(0)).containsEntry("SIDE", "BUY")
                .containsEntry("SHARES", 2000).containsEntry("VERSION", 1);
        assertThat(((java.sql.Date) rows.get(0).get("TRADE_DATE")).toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 23));
        assertThat(rows.get(0).get("PRICE").toString()).isEqualTo("1000.50");
        assertThat(((java.sql.Date) rows.get(1).get("TRADE_DATE")).toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        // holding 只剩代號、名稱、市場別，id 不變
        assertThat(jdbc.sql("SELECT id, code FROM holding ORDER BY id").query().listOfRows())
                .extracting(m -> m.get("ID")).containsExactlyElementsOf(idsBefore);
        // 新的 holding 從 14 開始，不會跟舊的撞
        jdbc.sql("INSERT INTO holding (code, name, market, created_at, updated_at) VALUES ('2317', '鴻海', 'TWSE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)").update();
        assertThat(jdbc.sql("SELECT MAX(id) FROM holding").query(Long.class).single()).isGreaterThan(idsBefore.get(1));
    }
}
