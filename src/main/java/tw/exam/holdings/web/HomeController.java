package tw.exam.holdings.web;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Clock clock;

    public HomeController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/")
    public String home(Model model) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        model.addAttribute("now", now.format(FORMAT));
        model.addAttribute("zone", now.getZone().getId());
        model.addAttribute("epochMillis", now.toInstant().toEpochMilli());
        return "home";
    }
}
