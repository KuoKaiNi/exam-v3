package tw.exam.holdings.web;

import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import jakarta.servlet.http.HttpServletResponse;
import tw.exam.holdings.AppException;

/** 網頁上的錯誤（例如修改已被刪掉的持股）顯示成一般頁面，不出現 Whitelabel 錯誤頁。 */
@ControllerAdvice(assignableTypes = HoldingPageController.class)
public class PageErrorHandler {

    @ExceptionHandler(AppException.class)
    public String handle(AppException e, Model model, HttpServletResponse response) {
        response.setStatus(e.status().value());
        model.addAttribute("error", e.getMessage());
        return "page-error";
    }
}
