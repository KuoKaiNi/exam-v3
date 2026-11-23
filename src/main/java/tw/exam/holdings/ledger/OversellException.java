package tw.exam.holdings.ledger;

import java.time.LocalDate;

/** 某天賣出的股數超過當時手上的股數。 */
public class OversellException extends RuntimeException {

    private final LocalDate date;
    private final long available;
    private final long requested;

    public OversellException(LocalDate date, long available, long requested) {
        super(date + " 賣出 " + requested + " 股，但當時只持有 " + available + " 股");
        this.date = date;
        this.available = available;
        this.requested = requested;
    }

    public LocalDate date() {
        return date;
    }

    public long available() {
        return available;
    }

    public long requested() {
        return requested;
    }
}
