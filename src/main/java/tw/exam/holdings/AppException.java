package tw.exam.holdings;

import org.springframework.http.HttpStatus;

/** 要回給使用者看的錯誤：網頁顯示 message，API 回 {"error":{"code","message"}}。 */
public class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AppException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static AppException validation(String message) {
        return new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public static AppException notFound(long id) {
        return new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "找不到這筆持股（id " + id + "）");
    }

    public static AppException duplicate(String code) {
        return new AppException(HttpStatus.CONFLICT, "DUPLICATE_CODE", code + " 已在持股中");
    }
}
