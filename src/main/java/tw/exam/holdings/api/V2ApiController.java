package tw.exam.holdings.api;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import tw.exam.holdings.AppException;
import tw.exam.holdings.holding.HoldingInput;
import tw.exam.holdings.holding.HoldingInput.Errors;
import tw.exam.holdings.holding.TradeService;
import tw.exam.holdings.idempotency.IdempotencyService;
import tw.exam.holdings.ledger.Side;
import tw.exam.holdings.ledger.Trade;
import tw.exam.holdings.portfolio.PortfolioService;
import tw.exam.holdings.portfolio.Position;
import tw.exam.holdings.api.V2Dtos.HoldingV2Dto;
import tw.exam.holdings.api.V2Dtos.SummaryV2Dto;
import tw.exam.holdings.api.V2Dtos.TradeDto;
import tw.exam.holdings.api.V2Dtos.TradeResultDto;

/** 2.0 API，文件見 API.md。新增、修改、刪除都接受 Idempotency-Key 標頭。 */
@RestController
@RequestMapping("/api/v2")
public class V2ApiController {

    private final TradeService trades;
    private final PortfolioService portfolio;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public V2ApiController(TradeService trades, PortfolioService portfolio, IdempotencyService idempotency, Clock clock) {
        this.trades = trades;
        this.portfolio = portfolio;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @GetMapping("/holdings")
    public List<HoldingV2Dto> holdings(@RequestParam(name = "include_closed", defaultValue = "true") boolean includeClosed) {
        return portfolio.positions().stream()
                .filter(p -> includeClosed || p.open())
                .map(p -> HoldingV2Dto.of(p, false)).toList();
    }

    @GetMapping("/holdings/{id}")
    public HoldingV2Dto holding(@PathVariable long id) {
        return HoldingV2Dto.of(portfolio.position(id), true);
    }

    @GetMapping("/summary")
    public SummaryV2Dto summary() {
        return SummaryV2Dto.of(portfolio.summaryV2());
    }

    @GetMapping("/trades/{id}")
    public TradeDto trade(@PathVariable long id) {
        Trade t = trades.get(id);
        return TradeDto.of(portfolio.position(t.holdingId()), t);
    }

    @PostMapping("/trades")
    public ResponseEntity<?> create(@RequestHeader(name = "Idempotency-Key", required = false) String key,
                                    @RequestBody(required = false) String body) {
        return idempotency.run(key, "POST /api/v2/trades\n" + body, () -> {
            JsonNode json = HoldingApiController.parseObject(body);
            Errors errors = new Errors();
            String code = HoldingApiController.code(json.get("code"), errors);
            Side side = HoldingInput.side(text(json.get("side"), "side", errors), errors);
            LocalDate date = HoldingInput.date(text(json.get("trade_date"), "trade_date", errors), LocalDate.now(clock), errors);
            Long shares = HoldingInput.shares(HoldingApiController.number(json.get("shares")),
                    HoldingApiController.notNumber(json.get("shares")), errors);
            BigDecimal price = HoldingInput.price(HoldingApiController.number(json.get("price")),
                    HoldingApiController.notNumber(json.get("price")), errors);
            errors.throwIfAny();
            Trade t = trades.add(code, side, date, shares, price);
            return ResponseEntity.status(HttpStatus.CREATED).body(result(t));
        });
    }

    /** 代號不能改（要換股票請刪掉重記）；version 必填，跟目前版本不同回 409 VERSION_CONFLICT。 */
    @PutMapping("/trades/{id}")
    public ResponseEntity<?> update(@PathVariable long id,
                                    @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                    @RequestBody(required = false) String body) {
        return idempotency.run(key, "PUT /api/v2/trades/" + id + "\n" + body, () -> {
            trades.get(id);
            JsonNode json = HoldingApiController.parseObject(body);
            Errors errors = new Errors();
            Integer version = version(json.get("version"), errors);
            Side side = HoldingInput.side(text(json.get("side"), "side", errors), errors);
            LocalDate date = HoldingInput.date(text(json.get("trade_date"), "trade_date", errors), LocalDate.now(clock), errors);
            Long shares = HoldingInput.shares(HoldingApiController.number(json.get("shares")),
                    HoldingApiController.notNumber(json.get("shares")), errors);
            BigDecimal price = HoldingInput.price(HoldingApiController.number(json.get("price")),
                    HoldingApiController.notNumber(json.get("price")), errors);
            errors.throwIfAny();
            return ResponseEntity.ok(result(trades.update(id, version, side, date, shares, price)));
        });
    }

    @DeleteMapping("/trades/{id}")
    public ResponseEntity<?> delete(@PathVariable long id, @RequestParam(name = "version", required = false) String version,
                                    @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return idempotency.run(key, "DELETE /api/v2/trades/" + id + "?version=" + version, () -> {
            trades.get(id);
            int v;
            try {
                v = Integer.parseInt(version == null ? "" : version.strip());
            } catch (NumberFormatException e) {
                throw AppException.validation("version 必填，要是整數（刪除前讀到的版本）");
            }
            trades.delete(id, v);
            return ResponseEntity.noContent().build();
        });
    }

    private TradeResultDto result(Trade t) {
        Position p = portfolio.position(t.holdingId());
        return new TradeResultDto(TradeDto.of(p, t), HoldingV2Dto.of(p, false));
    }

    private static String text(JsonNode node, String field, Errors errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            errors.add(field + " 要是字串");
            return "";
        }
        return node.asText();
    }

    private static Integer version(JsonNode node, Errors errors) {
        if (node == null || node.isNull() || !node.isIntegralNumber()) {
            errors.add("version 必填，要是整數（修改前讀到的版本）");
            return null;
        }
        return node.intValue();
    }
}
