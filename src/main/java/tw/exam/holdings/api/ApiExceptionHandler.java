package tw.exam.holdings.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import tw.exam.holdings.AppException;

/** API 的錯誤一律回 {"error":{"code","message"}}，App 才會顯示 message 而不是「資料格式錯誤」。 */
@RestControllerAdvice(assignableTypes = {HoldingApiController.class, V2ApiController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiError> app(AppException e) {
        return ApiError.response(e.status(), e.code(), e.getMessage());
    }

    /** /api/v1/holdings/abc：id 不是數字，就是找不到。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> badId(MethodArgumentTypeMismatchException e) {
        return ApiError.response(HttpStatus.NOT_FOUND, "NOT_FOUND", "找不到這筆持股（id " + e.getValue() + "）");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> other(Exception e) {
        log.error("API 發生未預期的錯誤", e);
        return ApiError.response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "伺服器發生錯誤，請稍後再試");
    }
}
