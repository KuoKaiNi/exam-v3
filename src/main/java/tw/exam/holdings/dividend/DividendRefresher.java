package tw.exam.holdings.dividend;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 背景定時同步官方除權息資料（跟收盤價一樣，測試時關掉）。 */
@Component
@ConditionalOnProperty(prefix = "quote", name = "refresh-enabled", havingValue = "true", matchIfMissing = true)
public class DividendRefresher {

    private final ExRightService service;

    public DividendRefresher(ExRightService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "${dividend.sync-check:10m}")
    public void sync() {
        service.requestSync();
    }
}
