package tw.exam.holdings.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** 錯誤回應一律是 {"error":{"code":"...","message":"..."}}。 */
public record ApiError(Body error) {

    public record Body(String code, String message) {
    }

    public static ResponseEntity<ApiError> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiError(new Body(code, message)));
    }
}
