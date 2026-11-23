package tw.exam.holdings.ledger;

public enum Side {
    BUY("買進"),
    SELL("賣出");

    private final String label;

    Side(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
