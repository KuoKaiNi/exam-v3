package tw.exam.holdings.quote;

/** 上市（臺灣證券交易所）或上櫃（證券櫃檯買賣中心）。名稱就是 API 回給 App 的值。 */
public enum Market {
    TWSE("上市"),
    TPEX("上櫃");

    private final String label;

    Market(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
