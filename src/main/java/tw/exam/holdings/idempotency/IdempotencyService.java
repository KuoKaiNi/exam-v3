package tw.exam.holdings.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import tw.exam.holdings.AppException;
import tw.exam.holdings.api.ApiError;

/**
 * 同一個 Idempotency-Key 只執行一次。App 2.0 送出後 10 秒沒回應會自動重送；網頁按兩下送出也一樣。
 *
 * <ul>
 *   <li>第一次：先登記「處理中」，做完把狀態碼和回應存起來。</li>
 *   <li>重送時第一次已完成：直接回存下來的回應，不會多記一筆。</li>
 *   <li>重送時第一次還在處理（例如正在向官方查代號）：等它做完再回同樣的結果，最多等 30 秒。</li>
 *   <li>同一個 key 但請求內容不同：回 422 IDEMPOTENCY_KEY_REUSED。</li>
 *   <li>5xx（例如官方連不上）不存，重送會重新做。存下的結果保留 24 小時。</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    static final MediaType JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);
    private static final int PROCESSING = 0;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration wait;

    @org.springframework.beans.factory.annotation.Autowired
    public IdempotencyService(JdbcClient jdbc, ObjectMapper mapper, Clock clock) {
        this(jdbc, mapper, clock, Duration.ofSeconds(30));
    }

    IdempotencyService(JdbcClient jdbc, ObjectMapper mapper, Clock clock, Duration wait) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.wait = wait;
    }

    private record Saved(String hash, int status, String body) {
    }

    /**
     * @param key     null 或空白表示不做重送保護
     * @param request 用來比對「同一個 key 是不是同一個請求」的內容（方法＋路徑＋body）
     */
    public ResponseEntity<?> run(String key, String request, Supplier<ResponseEntity<?>> action) {
        if (key == null || key.isBlank()) {
            return action.get();
        }
        if (key.length() > 100) {
            throw AppException.validation("Idempotency-Key 最長 100 個字");
        }
        String hash = sha256(request);
        try {
            jdbc.sql("INSERT INTO idempotency_key (idem_key, request_hash, status, created_at) VALUES (?, ?, ?, ?)")
                    .params(key, hash, PROCESSING, OffsetDateTime.now(clock)).update();
        } catch (DuplicateKeyException e) {
            return replay(key, hash);
        }
        ResponseEntity<?> response;
        try {
            response = action.get();
        } catch (AppException e) {
            if (e.status().is5xxServerError()) {
                forget(key);
                throw e;
            }
            response = ApiError.response(e.status(), e.code(), e.getMessage());
        } catch (RuntimeException e) {
            forget(key);
            throw e;
        }
        save(key, response);
        return response;
    }

    private ResponseEntity<?> replay(String key, String hash) {
        long until = System.nanoTime() + wait.toNanos();
        while (true) {
            Optional<Saved> saved = load(key);
            if (saved.isEmpty()) {
                // 第一次失敗（5xx）被清掉了：請使用者再送一次
                throw new AppException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "上一次送出的同一個請求沒有完成，請再送一次");
            }
            Saved s = saved.get();
            if (!s.hash().equals(hash)) {
                throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                        "這個 Idempotency-Key 已經用在別的請求上，每個新操作要換一個新的 key");
            }
            if (s.status() != PROCESSING) {
                ResponseEntity.BodyBuilder b = ResponseEntity.status(s.status()).header("Idempotent-Replayed", "true");
                return s.body() == null ? b.build() : b.contentType(JSON_UTF8).body(s.body());
            }
            if (System.nanoTime() > until) {
                throw new AppException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "同一個請求還在處理中，請稍後重新整理");
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AppException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "同一個請求還在處理中");
            }
        }
    }

    private Optional<Saved> load(String key) {
        return jdbc.sql("SELECT request_hash, status, response_body FROM idempotency_key WHERE idem_key = ?")
                .param(key)
                .query((rs, i) -> new Saved(rs.getString(1), rs.getInt(2), rs.getString(3)))
                .optional();
    }

    private void save(String key, ResponseEntity<?> response) {
        String body;
        try {
            body = response.getBody() == null ? null
                    : response.getBody() instanceof String str ? str : mapper.writeValueAsString(response.getBody());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        jdbc.sql("UPDATE idempotency_key SET status = ?, response_body = ? WHERE idem_key = ?")
                .params(response.getStatusCode().value(), body, key).update();
    }

    private void forget(String key) {
        jdbc.sql("DELETE FROM idempotency_key WHERE idem_key = ?").param(key).update();
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public void cleanup() {
        jdbc.sql("DELETE FROM idempotency_key WHERE created_at < ? AND status <> ?")
                .params(OffsetDateTime.now(clock).minusHours(24), PROCESSING).update();
        jdbc.sql("DELETE FROM idempotency_key WHERE created_at < ? AND status = ?")
                .params(OffsetDateTime.now(clock).minusHours(1), PROCESSING).update();
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
