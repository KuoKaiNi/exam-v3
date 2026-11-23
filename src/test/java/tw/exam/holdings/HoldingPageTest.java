package tw.exam.holdings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import tw.exam.holdings.holding.HoldingService;
import tw.exam.holdings.quote.Market;
import tw.exam.holdings.quote.Quote;
import tw.exam.holdings.quote.QuoteService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext
class HoldingPageTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    HoldingService service;

    @MockitoBean
    QuoteService quotes;

    @Test
    void createEditDeleteAndRejectDuplicate() throws Exception {
        Quote q = new Quote("2330", "台積電", Market.TWSE, LocalDate.of(2026, 11, 23), new BigDecimal("1100.00"));
        when(quotes.lookup("2330")).thenReturn(new QuoteService.Lookup.Found(Market.TWSE, "台積電", q));
        mvc.perform(post("/holdings").param("code", "2330").param("lots", "1").param("buyPrice", "1000"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/holdings"));
        assertThat(service.list()).hasSize(1);

        // 重複代號：顯示錯誤、不儲存
        mvc.perform(post("/holdings").param("code", " 2330 ").param("lots", "2").param("buyPrice", "900"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2330 已在持股中")));
        assertThat(service.list()).hasSize(1);
        assertThat(service.list().get(0).lots()).isEqualTo(1);

        long id = service.list().get(0).id();
        mvc.perform(post("/holdings/" + id).param("lots", "3").param("buyPrice", "980.5"))
                .andExpect(status().is3xxRedirection());
        assertThat(service.get(id).lots()).isEqualTo(3);
        assertThat(service.get(id).buyPrice()).isEqualByComparingTo("980.50");

        // 修改時填錯，原本的資料不變
        mvc.perform(post("/holdings/" + id).param("lots", "1000").param("buyPrice", "abc"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("張數要在 1 到 999 之間；買進價要填數字")));
        assertThat(service.get(id).lots()).isEqualTo(3);

        mvc.perform(get("/holdings"))
                .andExpect(content().string(containsString("980.50")))
                .andExpect(content().string(containsString("台積電")))
                .andExpect(content().string(containsString("未取得")));
        when(quotes.cached("2330")).thenReturn(Optional.of(q));
        // 3 張、買 980.5、收 1100：買 2,941,500＋手續費 4,191；賣 3,300,000－手續費 4,702－證交稅 9,900
        mvc.perform(get("/holdings"))
                .andExpect(content().string(containsString("1,100.00")))
                .andExpect(content().string(containsString("2026-11-23")))
                .andExpect(content().string(containsString("+339,707")))
                .andExpect(content().string(containsString("class=\"num profit\"")));

        mvc.perform(post("/holdings/" + id + "/delete")).andExpect(status().is3xxRedirection());
        assertThat(service.list()).isEmpty();

        mvc.perform(get("/holdings/" + id + "/edit")).andExpect(status().isNotFound());
    }

    @Test
    void unknownCodeAndOfficialDownAreNotSaved() throws Exception {
        when(quotes.lookup(anyString())).thenReturn(new QuoteService.Lookup.NotFound());
        mvc.perform(post("/holdings").param("code", "9999").param("lots", "1").param("buyPrice", "10"))
                .andExpect(content().string(containsString("查不到股票代號 9999")));
        when(quotes.lookup(anyString())).thenReturn(new QuoteService.Lookup.Unavailable("down"));
        mvc.perform(post("/holdings").param("code", "2317").param("lots", "1").param("buyPrice", "10"))
                .andExpect(content().string(containsString("官方行情網站暫時查不到")));
        assertThat(service.list()).isEmpty();
    }
}
