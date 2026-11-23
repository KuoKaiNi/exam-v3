package tw.exam.holdings.web;

import java.util.UUID;

/** 網頁的交易表單。欄位用字串收，填錯時把原字留在畫面上；requestId 防止按兩下送出記兩筆。 */
public class TradeForm {

    private String code;
    private String side = "BUY";
    private String tradeDate;
    private String shares;
    private String price;
    private String version;
    private String requestId = UUID.randomUUID().toString();

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public String getTradeDate() { return tradeDate; }
    public void setTradeDate(String tradeDate) { this.tradeDate = tradeDate; }
    public String getShares() { return shares; }
    public void setShares(String shares) { this.shares = shares; }
    public String getPrice() { return price; }
    public void setPrice(String price) { this.price = price; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    /** 送出失敗重新顯示表單時換一個新的編號，修正後再送才不會拿到上一次的錯誤結果。 */
    public void renewRequestId() {
        this.requestId = UUID.randomUUID().toString();
    }
}
