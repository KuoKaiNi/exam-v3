package tw.exam.holdings;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 正式機在海外、主機時區不是台北，但使用者都在台灣，台股也照台北時間開收盤。
 * 所以程式裡的「現在」一律從這個 Clock 取，不用主機預設時區。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock(@Value("${app.zone:Asia/Taipei}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
