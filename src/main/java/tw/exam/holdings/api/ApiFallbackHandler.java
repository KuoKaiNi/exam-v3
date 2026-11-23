package tw.exam.holdings.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 走不到 API controller 的錯誤（路徑不存在、方法不對）：/api 底下也回 {"error":{...}} 格式；
 * 網頁的部分維持 Spring 預設。
 */
@RestControllerAdvice
public class ApiFallbackHandler {

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> notFound(NoResourceFoundException e, HttpServletRequest req) throws Exception {
        if (!isApi(req)) {
            throw e;
        }
        return ApiError.response(HttpStatus.NOT_FOUND, "NOT_FOUND", "沒有這個 API：" + req.getRequestURI());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> method(HttpRequestMethodNotSupportedException e, HttpServletRequest req) throws Exception {
        if (!isApi(req)) {
            throw e;
        }
        return ApiError.response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                req.getRequestURI() + " 不接受 " + req.getMethod());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<?> mediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest req) throws Exception {
        if (!isApi(req)) {
            throw e;
        }
        return ApiError.response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "請求內容要是 JSON");
    }

    private static boolean isApi(HttpServletRequest req) {
        return req.getRequestURI().startsWith("/api/");
    }
}
