package tw.exam.holdings.web;

/** 網頁表單；欄位用字串收，檢查交給 HoldingInput，才能把使用者填的原字留在畫面上。 */
public class HoldingForm {

    private String code;
    private String lots;
    private String buyPrice;

    public HoldingForm() {
    }

    public HoldingForm(String code, String lots, String buyPrice) {
        this.code = code;
        this.lots = lots;
        this.buyPrice = buyPrice;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getLots() {
        return lots;
    }

    public void setLots(String lots) {
        this.lots = lots;
    }

    public String getBuyPrice() {
        return buyPrice;
    }

    public void setBuyPrice(String buyPrice) {
        this.buyPrice = buyPrice;
    }
}
