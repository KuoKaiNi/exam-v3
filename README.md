# 持股記帳

記下自己每一次買進、賣出台股，系統用證交所、櫃買中心公布的收盤價和除權除息資料，算出平均成本、已實現與未實現損益、領到的股利，以及含息總報酬。

2.0 的 API 文件在 [`API.md`](API.md)；從 1.0 升級看 [`UPGRADE.md`](UPGRADE.md)。

## 需要先裝

- **Java 17 以上**（JDK，例如 Temurin 17／21）。`java -version` 要看得到 17 以上。
- 不用另外裝 Maven，repo 裡附了 Maven Wrapper（`./mvnw`），第一次執行會自己下載。

## 啟動

```bash
./mvnw spring-boot:run
```

Windows 用 `mvnw.cmd spring-boot:run`。

啟動後打開 <http://localhost:8080/>。

想換埠號：`PORT=8085 ./mvnw spring-boot:run`。

第一次啟動會下載 Maven 與相依套件，需要網路，約一兩分鐘。資料存在專案目錄下的 `data/`。

## 畫面

| 網址 | 內容 |
|---|---|
| `/` | 首頁：標題與目前時間 |
| `/holdings` | 持股清單：股數、平均成本、未實現、已實現、現金股利、含息總報酬與總計；記一筆交易 |
| `/holdings/{id}` | 單檔明細：交易紀錄（修改、刪除）、持有期間的除權息 |
| `/api/v2/...` | 給 App 2.0 用的 API，見 `API.md` |
| `/api/v1/...` | 給手機 App 1.0 用的 API（見下面，2.0 起的對應方式見 `API.md` 最後一節） |

## 手機 App 1.0 用的 API

照《App 1.0 API 規格》實作，路徑開頭 `/api/v1`，欄位用小寫加底線，錯誤一律回 `{"error":{"code":"...","message":"..."}}`。

| 方法 | 路徑 | 成功 | 失敗 |
|---|---|---|---|
| GET | `/api/v1/holdings` | 200，持股陣列 | |
| GET | `/api/v1/holdings/{id}` | 200 | 404 `NOT_FOUND` |
| POST | `/api/v1/holdings` | 201 | 400 `VALIDATION_ERROR`、404 `STOCK_NOT_FOUND`、409 `DUPLICATE_CODE`、503 `QUOTE_UNAVAILABLE`（官方連不上，見 `questions.md` Q3） |
| PUT | `/api/v1/holdings/{id}` | 200 | 400 `VALIDATION_ERROR`、404 `NOT_FOUND` |
| DELETE | `/api/v1/holdings/{id}` | 204 | 404 `NOT_FOUND` |
| GET | `/api/v1/summary` | 200 | |

```bash
curl -X POST localhost:8080/api/v1/holdings -H 'Content-Type: application/json' -d '{"code":"2330","lots":1,"buy_price":1000}'
curl localhost:8080/api/v1/summary
```

App 1.0 的伺服器網址預設是 `http://10.0.2.2:8080`，跟這個專案的預設埠號一樣，用 Android 模擬器時不用改設定。換了埠號就到 App 右上角「設定」改。

## 官方行情

股票名稱和收盤價只取自官方：上市查臺灣證券交易所（`/rwd/zh/afterTrading/STOCK_DAY`），上櫃查證券櫃檯買賣中心（`/www/zh-tw/afterTrading/tradingStock`）。

- 新增持股時只填代號，系統同時查上市、上櫃，哪邊有就是哪個市場。兩邊都正常回「沒有」才算代號不存在；有一邊連不上就顯示「官方行情網站暫時查不到」，資料不儲存。最多約 8 秒有結果。
- 打開清單**不會**向官方查，只讀伺服器記住的收盤價。
- 伺服器每 5 分鐘在背景檢查一次：平日 13:30 以後還沒拿到當天收盤價的股票，每 20 分鐘重查一次，拿到就不再查。官方公布後最晚約 25 分鐘清單上看得到，不用重開伺服器。
- 查證交所時每次至少間隔 2 秒；看到像被擋的回應（HTTP 403、429、轉址或回 HTML）就 10 分鐘內不再查那個網站。
- 官方連不上時清單照常顯示，收盤價那格顯示「未取得」。

### 換成別的行情伺服器

主機部分用環境變數換，路徑和參數不變：

```bash
TWSE_BASE_URL=http://localhost:9000 TPEX_BASE_URL=http://localhost:9000 ./mvnw spring-boot:run
```

| 環境變數 | 預設 | 對應設定（`application.properties`） |
|---|---|---|
| `TWSE_BASE_URL` | `https://www.twse.com.tw` | `quote.twse-base-url` |
| `TPEX_BASE_URL` | `https://www.tpex.org.tw` | `quote.tpex-base-url` |

結尾有沒有 `/` 都可以。換主機後要重新啟動伺服器。

查到的收盤價會存進資料庫（`quote_cache`），重啟後直接顯示上次的收盤價，不必重查官方。每筆會記下是從哪個主機查到的，換了主機（例如換成測試伺服器）時舊主機的資料不沿用，會向新主機重查。

## 除權息

依交易日期自動計算，不用自己輸入。資料取自證交所「除權除息計算結果表」（TWT49U，「權」「權息」另查明細 TWT49UDetail）與櫃買中心 exDailyQ，主機一樣由 `TWSE_BASE_URL`、`TPEX_BASE_URL` 決定（路徑不變）。伺服器在背景按年抓整個市場的表存進資料庫，今年的表每 3 小時重抓。規則見 `API.md`「計算規則」。

## 盈虧怎麼算（1.0 的單筆試算規則，2.0 沿用手續費與證交稅）

「假設現在用收盤價全部賣出，淨賺多少」，程式在 `PnlCalculator.java`：

| 項目 | 規則 |
|---|---|
| 手續費 | 成交金額 × 0.1425%，無條件捨去到元，每筆最低 20 元；買進、賣出各收一次 |
| 證交稅 | 只有賣出收；一般股票 0.3%，ETF（代號 00 開頭）0.1%；無條件捨去到元 |
| 盈虧 | 賣出金額 − 賣出手續費 − 證交稅 − 買進金額 − 買進手續費 |
| 收益率 | 盈虧 ÷（買進金額 ＋ 買進手續費）× 100，四捨五入到小數第二位 |

清單上紅色是賺、綠色是虧。總計放在清單最上面，收盤價未取得的那幾筆只算進總成本。

## 測試

```bash
./mvnw test
```

測試不會連到證交所或櫃買中心：解析用存下來的真實回應（`src/test/resources/official/`），查詢流程用測試裡自己開的本機假伺服器。

## 資料庫

用 **H2 檔案模式**，資料存在專案目錄下的 `data/holdings.mv.db`。
選它是因為主考官 clone 下來不用另外裝資料庫或 Docker，一行指令就能跑；資料表一樣用 Flyway 建立（`src/main/resources/db/migration/`），之後要換 PostgreSQL 只要改連線設定、補驅動程式。

想清空資料：停掉伺服器後刪掉 `data/` 資料夾。

## 時間

首頁的「目前時間」一律是台北時間（`Asia/Taipei`），跟主機的時區設定無關。
