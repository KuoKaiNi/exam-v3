package tw.exam.holdings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import tw.exam.holdings.holding.HoldingInput;
import tw.exam.holdings.holding.HoldingInput.Errors;

class HoldingInputTest {

    @Test
    void codeIsTrimmedAndUppercased() {
        Errors e = new Errors();
        assertThat(HoldingInput.code(" 00631l ", e)).isEqualTo("00631L");
        assertThat(HoldingInput.code("2330", e)).isEqualTo("2330");
        assertThat(HoldingInput.code("２３３０", e)).isEqualTo("2330");
        e.throwIfAny();
    }

    @Test
    void badCodes() {
        for (String bad : new String[] {null, "", " ", "233", "2330ABC", "台積電", "AB12"}) {
            Errors e = new Errors();
            assertThat(HoldingInput.code(bad, e)).isNull();
            assertThatThrownBy(e::throwIfAny).isInstanceOf(AppException.class);
        }
    }

    @Test
    void lotsRange() {
        Errors e = new Errors();
        assertThat(HoldingInput.lots(new BigDecimal("1"), false, e)).isEqualTo(1);
        assertThat(HoldingInput.lots(new BigDecimal("999"), false, e)).isEqualTo(999);
        assertThat(HoldingInput.lots(new BigDecimal("2.0"), false, e)).isEqualTo(2);
        e.throwIfAny();
        for (String bad : new String[] {"0", "1000", "-1", "1.5"}) {
            Errors e2 = new Errors();
            assertThat(HoldingInput.lots(new BigDecimal(bad), false, e2)).isNull();
            assertThatThrownBy(e2::throwIfAny).hasMessageContaining("張數");
        }
        Errors e3 = new Errors();
        HoldingInput.lots(null, true, e3);
        assertThatThrownBy(e3::throwIfAny).hasMessage("張數要填整數");
    }

    @Test
    void priceRules() {
        Errors e = new Errors();
        assertThat(HoldingInput.buyPrice(new BigDecimal("1000"), false, e)).isEqualByComparingTo("1000.00");
        assertThat(HoldingInput.buyPrice(new BigDecimal("0.01"), false, e)).isEqualByComparingTo("0.01");
        assertThat(HoldingInput.buyPrice(new BigDecimal("10.500"), false, e)).isEqualByComparingTo("10.50");
        e.throwIfAny();
        for (String bad : new String[] {"0", "-1", "10.555", "100000000"}) {
            Errors e2 = new Errors();
            assertThat(HoldingInput.buyPrice(new BigDecimal(bad), false, e2)).isNull();
            assertThatThrownBy(e2::throwIfAny).hasMessageContaining("買進價");
        }
    }

    @Test
    void allErrorsReportedTogether() {
        Errors e = new Errors();
        HoldingInput.code("", e);
        HoldingInput.lots(null, false, e);
        HoldingInput.buyPrice(null, false, e);
        assertThatThrownBy(e::throwIfAny).hasMessage("股票代號必填；張數必填；買進價必填");
    }
}
