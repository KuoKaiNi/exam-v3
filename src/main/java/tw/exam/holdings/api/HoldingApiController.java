package tw.exam.holdings.api;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import tw.exam.holdings.AppException;
import tw.exam.holdings.holding.Holding;
import tw.exam.holdings.holding.HoldingInput;
import tw.exam.holdings.holding.HoldingInput.Errors;
import tw.exam.holdings.holding.HoldingService;
import tw.exam.holdings.holding.TradeService;
import tw.exam.holdings.portfolio.PortfolioService;

/**
 * App 1.0 用的 API，規格見《App 1.0 API 規格》。App 已發布不會再改，這裡的欄位、型別、狀態碼都不能動。
 *
 * <p>請求內容自己解析：App 1.0 在張數填「1.5」「abc」時會原樣送字串、空白送 null，
 * 用 Spring 預設的轉換會把 1.5 捨成 1 或回 Spring 自己的錯誤格式，App 會顯示「資料格式錯誤」。
 */
@RestController
@RequestMapping("/api/v1")
public class HoldingApiController {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final HoldingService holdings;
    private final TradeService tradeService;
    private final PortfolioService portfolio;

    public HoldingApiController(HoldingService holdings, TradeService tradeService, PortfolioService portfolio) {
        this.holdings = holdings;
        this.tradeService = tradeService;
        this.portfolio = portfolio;
    }

    /** 2.0 起：已經全部賣掉的不列出（1.0 沒有「已賣出」的概念）。 */
    @GetMapping("/holdings")
    public List<HoldingDto> list() {
        return portfolio.openPositions().stream().map(HoldingDto::of).toList();
    }

    @GetMapping("/holdings/{id}")
    public HoldingDto get(@PathVariable long id) {
        return HoldingDto.of(portfolio.position(holdings.get(id)));
    }

    @PostMapping("/holdings")
    public ResponseEntity<HoldingDto> create(@RequestBody(required = false) String body) {
        JsonNode json = parseObject(body);
        Errors errors = new Errors();
        String code = code(json.get("code"), errors);
        Integer lots = lots(json.get("lots"), errors);
        BigDecimal price = price(json.get("buy_price"), errors);
        errors.throwIfAny();
        Holding h = tradeService.v1Create(code, lots, price);
        return ResponseEntity.status(HttpStatus.CREATED).body(HoldingDto.of(portfolio.position(h)));
    }

    /** 代號不能改：請求裡有 code 也不理它。 */
    @PutMapping("/holdings/{id}")
    public HoldingDto update(@PathVariable long id, @RequestBody(required = false) String body) {
        holdings.get(id);
        JsonNode json = parseObject(body);
        Errors errors = new Errors();
        Integer lots = lots(json.get("lots"), errors);
        BigDecimal price = price(json.get("buy_price"), errors);
        errors.throwIfAny();
        return HoldingDto.of(portfolio.position(tradeService.v1Update(id, lots, price)));
    }

    @DeleteMapping("/holdings/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        tradeService.v1Delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/summary")
    public SummaryDto summary() {
        return SummaryDto.of(portfolio.summary());
    }

    static JsonNode parseObject(String body) {
        if (body == null || body.isBlank()) {
            throw AppException.validation("請求內容是空的，要送 JSON 物件");
        }
        JsonNode json;
        try {
            json = JSON.readTree(body);
        } catch (Exception e) {
            throw AppException.validation("請求內容不是正確的 JSON");
        }
        if (json == null || !json.isObject()) {
            throw AppException.validation("請求內容要是 JSON 物件");
        }
        return json;
    }

    static String code(JsonNode node, Errors errors) {
        if (node != null && !node.isNull() && !node.isTextual()) {
            errors.add("股票代號要是字串，例如 \"2330\"");
            return null;
        }
        return HoldingInput.code(node == null || node.isNull() ? null : node.asText(), errors);
    }

    private static Integer lots(JsonNode node, Errors errors) {
        return HoldingInput.lots(number(node), notNumber(node), errors);
    }

    static BigDecimal price(JsonNode node, Errors errors) {
        return HoldingInput.buyPrice(number(node), notNumber(node), errors);
    }

    static BigDecimal number(JsonNode node) {
        return node != null && node.isNumber() ? node.decimalValue() : null;
    }

    /** 有給值但不是 JSON 數字（字串、布林、陣列…）。 */
    static boolean notNumber(JsonNode node) {
        return node != null && !node.isNull() && !node.isNumber();
    }
}
