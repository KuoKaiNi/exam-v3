package tw.exam.holdings.web;

import java.math.BigDecimal;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import tw.exam.holdings.AppException;
import tw.exam.holdings.holding.Holding;
import tw.exam.holdings.holding.HoldingInput;
import tw.exam.holdings.holding.HoldingInput.Errors;
import tw.exam.holdings.holding.HoldingInput.ParsedNumber;
import tw.exam.holdings.holding.HoldingService;
import tw.exam.holdings.portfolio.PortfolioService;

@Controller
public class HoldingPageController {

    private final HoldingService service;
    private final PortfolioService portfolio;

    public HoldingPageController(HoldingService service, PortfolioService portfolio) {
        this.service = service;
        this.portfolio = portfolio;
    }

    @GetMapping("/holdings")
    public String list(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new HoldingForm());
        }
        model.addAttribute("positions", portfolio.positions());
        return "holdings";
    }

    @PostMapping("/holdings")
    public String create(@ModelAttribute("form") HoldingForm form, Model model, RedirectAttributes redirect) {
        try {
            Errors errors = new Errors();
            String code = HoldingInput.code(form.getCode(), errors);
            Integer lots = lots(form.getLots(), errors);
            BigDecimal price = price(form.getBuyPrice(), errors);
            errors.throwIfAny();
            Holding h = service.create(code, lots, price);
            redirect.addFlashAttribute("message", "已新增 " + h.code());
            return "redirect:/holdings";
        } catch (AppException e) {
            model.addAttribute("error", e.getMessage());
            return list(model);
        }
    }

    @GetMapping("/holdings/{id}/edit")
    public String edit(@PathVariable long id, Model model) {
        Holding h = service.get(id);
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new HoldingForm(h.code(), String.valueOf(h.lots()), h.buyPrice().toPlainString()));
        }
        model.addAttribute("holding", h);
        return "holding-edit";
    }

    @PostMapping("/holdings/{id}")
    public String update(@PathVariable long id, @ModelAttribute("form") HoldingForm form, Model model,
                         RedirectAttributes redirect) {
        try {
            Errors errors = new Errors();
            Integer lots = lots(form.getLots(), errors);
            BigDecimal price = price(form.getBuyPrice(), errors);
            errors.throwIfAny();
            Holding h = service.update(id, lots, price);
            redirect.addFlashAttribute("message", "已修改 " + h.code());
            return "redirect:/holdings";
        } catch (AppException e) {
            if ("NOT_FOUND".equals(e.code())) {
                throw e;
            }
            model.addAttribute("error", e.getMessage());
            return edit(id, model);
        }
    }

    @PostMapping("/holdings/{id}/delete")
    public String delete(@PathVariable long id, RedirectAttributes redirect) {
        Holding h = service.get(id);
        service.delete(id);
        redirect.addFlashAttribute("message", "已刪除 " + h.code());
        return "redirect:/holdings";
    }

    private static Integer lots(String text, Errors errors) {
        ParsedNumber n = HoldingInput.parseText(text);
        return HoldingInput.lots(n.value(), n.notNumber(), errors);
    }

    private static BigDecimal price(String text, Errors errors) {
        ParsedNumber n = HoldingInput.parseText(text);
        return HoldingInput.buyPrice(n.value(), n.notNumber(), errors);
    }
}
