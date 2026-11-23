# 持股記帳 API（2.0）

給 App 2.0 團隊串接用。App 1.0 用的 `/api/v1` 照舊保留，規格沒有改，2.0 起的對應方式寫在最後一節。

## 共通規則

- 路徑開頭 `/api/v2`，回應是 JSON（UTF-8），欄位用小寫加底線。
- 金額（`total_cost`、`pnl`、`cash` 這類）是整數，單位元。價格是數字，最多兩位小數。每股股利、每千股配股照官方給的位數，可能有 8 位小數。
- 日期一律 `YYYY-MM-DD`（西元年、台北時間）。
- 規格上的欄位一定會出現；取不到值時是 `null`，不會省略。之後可能加欄位，請忽略不認得的欄位。
- 錯誤一律回：

```json
{ "error": { "code": "INSUFFICIENT_SHARES", "message": "2330 在 2025-08-01 賣出 1500 股，但當時只持有 1000 股" } }
```

`message` 是給使用者看的中文，可以直接顯示。

### 逾時重送：`Idempotency-Key`

手機網路不穩時 App 會自動重送，為了不讓同一筆交易記兩次，**新增、修改、刪除交易都請帶 `Idempotency-Key` 標頭**：

- 使用者每按一次「送出」就產生一個新的 UUID，**自動重送時用同一個**。
- 伺服器收到同一個 key：第一次已經做完，直接回第一次的結果（同樣的狀態碼和內容，多一個標頭 `Idempotent-Replayed: true`），不會再記一筆；第一次還在處理（例如正在向官方查代號），會等它做完再回，最多等 30 秒。
- 同一個 key 但請求內容不同：回 `422 IDEMPOTENCY_KEY_REUSED`。代表 App 重複用了 key，是 App 的 bug。
- 第一次是 5xx（例如官方網站連不上）的不會記住，重送會重新做。
- key 保留 24 小時，最長 100 個字。
- 沒帶 key 也能用，只是沒有重送保護。

### 兩台裝置同時改：`version`

每筆交易都有 `version`（從 1 開始，每改一次加 1）。修改、刪除時要帶**讀到的 version**：

- 對得上才改，回應裡是新的 version。
- 對不上（別台裝置先改過或先刪了）回 `409 VERSION_CONFLICT`，請 App 重新讀取後讓使用者決定。

另外伺服器每次新增、修改、刪除都會鎖住那一檔股票、用改完後的整條交易紀錄檢查股數，兩台裝置同時賣出也不會讓股數變成負的。

### 錯誤代碼

| HTTP | code | 什麼時候 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 欄位缺少、型別不對、超出範圍。多個欄位錯時 `message` 用「；」串起來 |
| 404 | `NOT_FOUND` | 持股或交易的 id 不存在 |
| 404 | `STOCK_NOT_FOUND` | 新增時代號在上市、上櫃都查不到 |
| 409 | `INSUFFICIENT_SHARES` | 賣出（或修改、刪除後）會讓某一天的股數不夠賣 |
| 409 | `VERSION_CONFLICT` | version 對不上 |
| 409 | `REQUEST_IN_PROGRESS` | 同一個 Idempotency-Key 的第一次請求還在處理超過 30 秒，或已失敗 |
| 422 | `IDEMPOTENCY_KEY_REUSED` | 同一個 key 用在不同內容的請求 |
| 503 | `QUOTE_UNAVAILABLE` | 第一次記某個代號時官方網站連不上，無法確認代號存在；資料沒存，請稍後重試 |
| 500 | `INTERNAL_ERROR` | 伺服器錯誤 |

## 計算規則

| 項目 | 規則 |
|---|---|
| 手續費 | 成交金額 × 0.1425%，無條件捨去到元，每筆最低 20 元；買進、賣出各收。成交金額＝價格 × 股數，四捨五入到元 |
| 證交稅 | 賣出收。一般股票 0.3%，ETF（代號 00 開頭）0.1%，無條件捨去到元 |
| 平均成本 `avg_cost` | 剩餘總成本 ÷ 股數，四捨五入到小數第二位。總成本含買進手續費；配股成本是 0 |
| 賣出移出的成本 | 剩餘總成本 × 賣出股數 ÷ 賣出前股數，四捨五入到元（平均成本法） |
| 已實現損益 | 每筆賣出：成交金額 − 手續費 − 證交稅 − 移出的成本 |
| 未實現損益 | 用最近收盤價把剩下的股數全部賣掉：成交金額 − 手續費 − 證交稅 − 剩餘總成本 |
| 誰領得到股利 | 除權息交易日**前一個交易日收盤時**的持股。也就是除權息日之前的交易都算，除權息當天買的不算、當天賣的照領 |
| 現金股利 | 領取股數 × 每股現金股利，元以下捨去 |
| 配股 | 領取股數 × 每千股無償配股 ÷ 1,000，不足一股捨去；記在除權息交易日當天 |
| 含息總報酬 `total_return` | 已實現損益＋未實現損益＋累計現金股利 |
| 預估二代健保補充保費 | 股利所得（現金股利＋股票股利面額每股 10 元）單次 2 萬元以上 × 2.11%，上限 1,000 萬元，四捨五入到元。只是預估，不扣進任何損益 |

同一天的順序：先處理當天的除權息，再處理當天的交易；交易先買後賣（可以當沖）。

### 除權息資料從哪來、什麼時候會有

- 上市：臺灣證券交易所「除權除息計算結果表」TWT49U；證交所這張表只有「權值+息值」合計，所以「權」「權息」的股票另外查明細 TWT49UDetail。上櫃：證券櫃檯買賣中心 exDailyQ。
- 伺服器在背景按年抓整個市場的表存起來：最早一筆交易的年份到今年，今年的表每 3 小時重抓。**第一次記某一檔、或補登更早年份的交易時，除權息資料可能要幾秒到一兩分鐘才會補齊**，這段時間 `dividend_data_complete` 是 `false`、股利可能少算。App 可以在看到 `false` 時顯示「股利計算中」，稍後重新整理。
- 交易日期最早到 2003-05-05（證交所股利資料從這天開始），不能晚於今天。

### 股票分割、減資（這一版不處理）

證交所、櫃買中心的除權除息表不含分割、減資（表上註明「本表不含除息併案辦理退還股款減資或分割減資之資料」），2.0 也沒有別的資料來源，所以**遇到時股數和成本都不會自動調整**。使用者會看到：

- **分割**（例如 1 股拆成 4 股）：股數還是分割前的，收盤價卻是分割後的，未實現損益和含息總報酬會變成一大筆虧損；之後的股利也會用分割前的股數算，少算。
- **減資**（退還股款或彌補虧損）：股數還是減資前的，市值、未實現損益算多了；退還的股款不會算進任何損益。
- 暫時的處理方式：使用者在分割或減資生效日記一筆調整（例如分割時把原本的買進改成分割後的股數、每股價格除以分割比例）。這需要 App 提示，2.0 沒有做。

## 持股

### `GET /api/v2/holdings`

200，持股陣列（沒有就是 `[]`），依代號排序。預設也列出已經全部賣掉的（`shares` 是 0），只要持有中的加 `?include_closed=false`。

### `GET /api/v2/holdings/{id}`

200，持股物件，多 `trades`（交易紀錄，依日期排序）和 `dividends`（持有期間的除權息）。404 `NOT_FOUND`。

```json
{
  "id": 1,
  "code": "2884",
  "name": "玉山金",
  "market": "TWSE",
  "shares": 1020,
  "avg_cost": 29.75,
  "total_cost": 30340,
  "current_price": 38.70,
  "price_date": "2026-11-23",
  "market_value": 39474,
  "unrealized_pnl": 8960,
  "unrealized_pct": 29.53,
  "realized_pnl": 1617,
  "cash_dividends": 3828,
  "stock_dividend_shares": 20,
  "total_return": 14405,
  "dividend_data_complete": true,
  "trades": [
    { "id": 1, "holding_id": 1, "code": "2884", "side": "BUY",  "trade_date": "2025-07-01", "shares": 2000, "price": 30.00, "version": 1, "realized_pnl": null },
    { "id": 2, "holding_id": 1, "code": "2884", "side": "SELL", "trade_date": "2025-09-01", "shares": 1000, "price": 31.50, "version": 1, "realized_pnl": 1617 }
  ],
  "dividends": [
    { "ex_date": "2025-07-22", "kind": "權息", "eligible_shares": 2000, "cash_per_share": 1.2, "stock_per_thousand": 10, "cash": 2400, "stock_shares": 20, "nhi_premium_estimate": 0 },
    { "ex_date": "2026-07-01", "kind": "息", "eligible_shares": 1020, "cash_per_share": 1.4, "stock_per_thousand": 0, "cash": 1428, "stock_shares": 0, "nhi_premium_estimate": 0 }
  ]
}
```

（實際從伺服器拿到的回應，2026-11-23。2,000 股＋配 20 股＝2,020 股；成本 60,085 ÷ 2,020 ＝ 29.75；賣 1,000 股後剩 1,020 股，平均成本不變。）

| 欄位 | 型別 | 說明 |
|---|---|---|
| `id` | 整數 | 持股 id（跟 1.0 的 id 是同一個） |
| `code`、`name` | 字串 | 代號、名稱 |
| `market` | 字串 | `TWSE` 上市、`TPEX` 上櫃 |
| `shares` | 整數 | 目前股數（含配股），可以是零股；全部賣掉是 0 |
| `avg_cost` | 數字或 null | 平均成本（含買進手續費）；`shares` 是 0 時 null |
| `total_cost` | 整數 | 剩餘總成本 |
| `current_price`、`price_date` | 數字、字串或 null | 最近收盤價與日期；取不到時 null |
| `market_value` | 整數或 null | 收盤價 × 股數；有股數但沒收盤價時 null，全部賣掉是 0 |
| `unrealized_pnl` | 整數或 null | 未實現損益；有股數但沒收盤價時 null |
| `unrealized_pct` | 數字或 null | 未實現 ÷ 剩餘總成本 × 100，四捨五入到小數第二位 |
| `realized_pnl` | 整數 | 已實現損益合計 |
| `cash_dividends` | 整數 | 累計現金股利 |
| `stock_dividend_shares` | 整數 | 累計配股股數 |
| `total_return` | 整數或 null | 含息總報酬；`unrealized_pnl` 是 null 時也是 null |
| `dividend_data_complete` | 布林 | 持有期間的官方除權息資料都拿到了；`false` 時股利可能少算 |

交易物件的 `realized_pnl` 只有賣出才有值（那一筆賣出的已實現損益），買進是 `null`。

除權息物件：

| 欄位 | 說明 |
|---|---|
| `ex_date` | 除權息交易日（也是入帳日） |
| `kind` | 官方「權/息」欄原字：證交所 `息`、`權`、`權息`；櫃買中心 `除息`、`除權`、`除權息` |
| `eligible_shares` | 領取股數（前一個交易日收盤時的持股） |
| `cash_per_share`、`stock_per_thousand` | 每股現金股利、每千股無償配股；證交所明細還沒查到時 null |
| `cash`、`stock_shares` | 這次領到的現金、配股；明細還沒查到時 null |
| `nhi_premium_estimate` | 預估二代健保補充保費；明細還沒查到時 null |

### `GET /api/v2/summary`

```json
{
  "total_cost": 30340,
  "market_value": 39474,
  "unrealized_pnl": 8960,
  "realized_pnl": 1617,
  "cash_dividends": 3828,
  "total_return": 14405,
  "price_missing": 0,
  "dividend_data_incomplete": 0
}
```

`price_missing`：有股數但取不到收盤價的檔數，這些不算進 `market_value`、`unrealized_pnl`，所以 `total_return` 會偏低。`dividend_data_incomplete`：`dividend_data_complete` 是 `false` 的檔數。

## 交易

### `POST /api/v2/trades`

標頭 `Idempotency-Key: <UUID>`（建議一定要帶）。

```json
{ "code": "2884", "side": "BUY", "trade_date": "2025-07-01", "shares": 2000, "price": 30.00 }
```

| 欄位 | 規則 |
|---|---|
| `code` | 字串，必填；4 碼數字＋最多 2 碼英數字，全形會轉半形 |
| `side` | 字串，`BUY` 或 `SELL` |
| `trade_date` | 字串 `YYYY-MM-DD`，2003-05-05 到今天（台北時間） |
| `shares` | 整數，1 ～ 999,999（零股可以） |
| `price` | 數字，大於 0，最多兩位小數 |

張數、價格要送 JSON 數字，送字串（`"300"`）回 400。

成功 201：

```json
{
  "trade":   { "id": 2, "holding_id": 1, "code": "2884", "side": "SELL", "trade_date": "2025-09-01", "shares": 1000, "price": 31.50, "version": 1, "realized_pnl": 1617 },
  "holding": { "id": 1, "code": "2884", "...": "跟 GET /api/v2/holdings 的持股物件一樣（不含 trades、dividends）" }
}
```

- 第一次記某個代號會向官方查是上市還是上櫃，最多約 8 秒。App 的 10 秒逾時重送請帶同一個 Idempotency-Key。
- 賣出的代號沒有持股，或賣超：409 `INSUFFICIENT_SHARES`。補登「比現有賣出更早的賣出」也會檢查整條時間軸。

### `PUT /api/v2/trades/{id}`

標頭 `Idempotency-Key`。代號不能改（要換股票請刪掉重記）。

```json
{ "side": "BUY", "trade_date": "2025-06-11", "shares": 1000, "price": 1065.00, "version": 1 }
```

成功 200，格式同 POST，`trade.version` 加 1。改完後股利會依新的日期、股數重算。會讓某一天賣超的修改（例如把買進日期改到賣出之後）回 409 `INSUFFICIENT_SHARES`。

### `DELETE /api/v2/trades/{id}?version=1`

標頭 `Idempotency-Key`。成功 204 沒有內容。刪掉這檔的最後一筆交易時，這檔股票也一起刪掉（`GET /api/v2/holdings/{id}` 之後回 404）。刪掉會讓之後的賣出不夠賣的，回 409 `INSUFFICIENT_SHARES`。

### `GET /api/v2/trades/{id}`

200，交易物件。404 `NOT_FOUND`。

## App 1.0 的 API（`/api/v1`）在 2.0 的行為

規格（欄位、型別、狀態碼）完全沒變。1.0 建的資料（每檔一筆買進）看到的數字跟升級前一模一樣。資料改成交易紀錄後的對應方式：

| 1.0 | 2.0 怎麼對應 |
|---|---|
| `GET /holdings` | 只列還持有中的（全部賣掉的不列）。`lots`＝股數 ÷ 1,000 **捨去**，零股看不到（300 股會顯示 0 張）；`buy_price`＝剩餘成本扣掉買進手續費 ÷ 股數；`pnl`、`return_pct`＝未實現損益與報酬率（不含已實現與股利）；盈虧照實際股數算 |
| `GET /summary` | 只算持有中的；`pnl` 是未實現損益合計 |
| `POST /holdings` | 記成今天（台北時間）的一筆買進。還持有中的代號回 409 `DUPLICATE_CODE`；已經全部賣掉的代號可以再新增 |
| `PUT /holdings/{id}` | 只有「只有一筆買進」的持股能改（1.0 建的都是）。有多筆交易的回 400 `VALIDATION_ERROR`，訊息請使用者改用網頁或 2.0 App |
| `DELETE /holdings/{id}` | 刪掉這檔和它所有的交易紀錄（含 2.0 記的） |

1.0 沒有 Idempotency-Key，也沒有 version，維持 1.0 的行為（後寫的蓋掉先寫的）。
