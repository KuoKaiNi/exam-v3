package tw.exam.holdings.quote;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** 測試用時鐘，可以往前撥。 */
public class MutableClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    public MutableClock(ZonedDateTime start) {
        this.now = start.toInstant();
        this.zone = start.getZone();
    }

    public void advance(Duration d) {
        now = now.plus(d);
    }

    public void set(ZonedDateTime t) {
        now = t.toInstant();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId z) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
