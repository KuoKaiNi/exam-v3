package tw.exam.holdings.holding;

import tw.exam.holdings.quote.Market;

/**
 * 一檔股票（2.0 起股數、成本都從交易紀錄算，這裡只記代號、名稱、市場別）。
 * id 沿用 1.0，App 1.0 記住的 id 升級後仍然有效。
 */
public record Holding(long id, String code, String name, Market market) {
}
