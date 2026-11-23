package tw.exam.holdings.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 一次除權息。cashPerShare、stockPerThousand 為 null 表示官方資料還沒拿到明細（例如證交所的「權」「權息」要另外查明細）。
 *
 * @param kind             官方的「權/息」欄原字（證交所：息、權、權息；櫃買中心：除息、除權、除權息）
 * @param cashPerShare     每股現金股利（元）
 * @param stockPerThousand 每千股無償配股（股），可能有小數，例如 150.00000327
 */
public record ExRight(String code, LocalDate exDate, String kind, BigDecimal cashPerShare, BigDecimal stockPerThousand) {

    public boolean known() {
        return cashPerShare != null && stockPerThousand != null;
    }
}
