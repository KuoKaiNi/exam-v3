package tw.exam.holdings.web;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.function.Supplier;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import tw.exam.holdings.AppException;
import tw.exam.holdings.api.ApiError;
import tw.exam.holdings.holding.HoldingInput;
import tw.exam.holdings.holding.HoldingInput.Errors;
import tw.exam.holdings.holding.HoldingInput.ParsedNumber;
import tw.exam.holdings.holding.TradeService;
import tw.exam.holdings.idempotency.IdempotencyService;
import tw.exam.holdings.ledger.Side;
import tw.exam.holdings.ledger.Trade;
import tw.exam.holdings.portfolio.PortfolioService;
import tw.exam.holdings.portfolio.Position;
import tw.exam.holdings.portfolio.SummaryV2;

@Controller
public class HoldingPageController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TradeService trades;
    private final PortfolioService portfolio;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public HoldingPageController(TradeService trades, PortfolioService portfolio, IdempotencyService idempotency,
                                 Clock clock) {
        this.trades = trades;
        this.portfolio = portfolio;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @GetMapping("/holdings")
    public String list(Model model) {
        if (!model.containsAttribute("form")) {
            TradeForm form = new TradeForm();
            form.setTradeDate(LocalDate.now(clock).toString());
            model.addAttribute("form", form);
        }
        var positions = portfolio.positions();
        model.addAttribute("positions", positions);
        model.addAttribute("summary", SummaryV2.of(positions));
        return "holdings";
    }

    @GetMapping("/holdings/{id}")
    public String detail(@PathVariable long id, Model model) {
        Position p = portfolio.position(id);
        if (!model.containsAttribute("form")) {
            TradeForm form = new TradeForm();
            form.setCode(p.holding().code());
            form.setTradeDate(LocalDate.now(clock).toString());
            model.addAttribute("form", form);
        }
        model.addAttribute("p", p);
        return "holding-detail";
    }

    /** 新增交易：從清單頁或明細頁送來，成功後回到原本的頁面。 */
    @PostMapping("/trades")
    public String create(@ModelAttribute("form") TradeForm form, @RequestParam(name = "back", required = false) String back,
                         Model model, RedirectAttributes redirect) {
        String error = run(form.getRequestId(), "web POST /trades " + describe(form), () -> {
            Errors errors = new Errors();
            String code = HoldingInput.code(form.getCode(), errors);
            Side side = HoldingInput.side(form.getSide(), errors);
            LocalDate date = HoldingInput.date(form.getTradeDate(), LocalDate.now(clock), errors);
            Long shares = shares(form.getShares(), errors);
            BigDecimal price = price(form.getPrice(), errors);
            errors.throwIfAny();
            Trade t = trades.add(code, side, date, shares, price);
            return ResponseEntity.ok().header("X-Holding-Id", String.valueOf(t.holdingId())).build();
        });
        if (error != null) {
            model.addAttribute("error", error);
            form.renewRequestId();
            if (back != null && back.matches("\\d+")) {
                return detail(Long.parseLong(back), model);
            }
            return list(model);
        }
        redirect.addFlashAttribute("message", "已記一筆" + ("SELL".equalsIgnoreCase(form.getSide()) ? "賣出" : "買進") + " "
                + form.getCode().strip());
        return back != null && back.matches("\\d+") ? "redirect:/holdings/" + back : "redirect:/holdings";
    }

    @GetMapping("/trades/{id}/edit")
    public String edit(@PathVariable long id, Model model) {
        Trade t = trades.get(id);
        Position p = portfolio.position(t.holdingId());
        if (!model.containsAttribute("form")) {
            TradeForm form = new TradeForm();
            form.setCode(p.holding().code());
            form.setSide(t.side().name());
            form.setTradeDate(t.date().toString());
            form.setShares(String.valueOf(t.shares()));
            form.setPrice(t.price().toPlainString());
            form.setVersion(String.valueOf(t.version()));
            model.addAttribute("form", form);
        }
        model.addAttribute("trade", t);
        model.addAttribute("p", p);
        return "trade-edit";
    }

    @PostMapping("/trades/{id}")
    public String update(@PathVariable long id, @ModelAttribute("form") TradeForm form, Model model,
                         RedirectAttributes redirect) {
        Trade before = trades.get(id);
        String error = run(form.getRequestId(), "web PUT /trades/" + id + " " + describe(form), () -> {
            Errors errors = new Errors();
            Side side = HoldingInput.side(form.getSide(), errors);
            LocalDate date = HoldingInput.date(form.getTradeDate(), LocalDate.now(clock), errors);
            Long shares = shares(form.getShares(), errors);
            BigDecimal price = price(form.getPrice(), errors);
            errors.throwIfAny();
            trades.update(id, version(form.getVersion()), side, date, shares, price);
            return ResponseEntity.ok().build();
        });
        if (error != null) {
            model.addAttribute("error", error);
            form.renewRequestId();
            return edit(id, model);
        }
        redirect.addFlashAttribute("message", "已修改交易");
        return "redirect:/holdings/" + before.holdingId();
    }

    @PostMapping("/trades/{id}/delete")
    public String delete(@PathVariable long id, @RequestParam(name = "version") String version,
                         RedirectAttributes redirect) {
        Trade t = trades.get(id);
        try {
            trades.delete(id, version(version));
        } catch (AppException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/holdings/" + t.holdingId();
        }
        redirect.addFlashAttribute("message", "已刪除交易");
        return portfolioHas(t.holdingId()) ? "redirect:/holdings/" + t.holdingId() : "redirect:/holdings";
    }

    private boolean portfolioHas(long holdingId) {
        return portfolio.positions().stream().anyMatch(p -> p.holding().id() == holdingId);
    }

    /** 跑一次（同一個 requestId 只做一次）；回傳錯誤訊息，成功回 null。 */
    private String run(String requestId, String request, Supplier<ResponseEntity<?>> action) {
        try {
            ResponseEntity<?> r = idempotency.run(requestId, request, action);
            if (r.getStatusCode().is2xxSuccessful()) {
                return null;
            }
            Object body = r.getBody();
            if (body instanceof ApiError e) {
                return e.error().message();
            }
            if (body instanceof String s) {
                JsonNode n = JSON.readTree(s);
                return n.path("error").path("message").asText("發生錯誤");
            }
            return "發生錯誤";
        } catch (AppException e) {
            return e.getMessage();
        } catch (Exception e) {
            return "發生錯誤：" + e.getMessage();
        }
    }

    private static String describe(TradeForm f) {
        return f.getCode() + "|" + f.getSide() + "|" + f.getTradeDate() + "|" + f.getShares() + "|" + f.getPrice() + "|" + f.getVersion();
    }

    private static int version(String text) {
        try {
            return Integer.parseInt(text == null ? "" : text.strip());
        } catch (NumberFormatException e) {
            throw AppException.validation("版本資料不見了，請重新整理後再改");
        }
    }

    private static Long shares(String text, Errors errors) {
        ParsedNumber n = HoldingInput.parseText(text);
        return HoldingInput.shares(n.value(), n.notNumber(), errors);
    }

    private static BigDecimal price(String text, Errors errors) {
        ParsedNumber n = HoldingInput.parseText(text);
        return HoldingInput.price(n.value(), n.notNumber(), errors);
    }
}
