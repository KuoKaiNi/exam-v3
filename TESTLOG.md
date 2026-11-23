# 操作測試紀錄

這裡記的是實際啟動伺服器、用瀏覽器表單、curl、App 1.0 操作時發現的問題。單元測試抓到的不在這裡。

| # | 發現方式 | 重現步驟 | 問題 | 修好的 commit |
|---|---|---|---|---|
| 1 | 啟動後用 curl 送新增表單，看回應標頭 | `curl -i -d 'code=2330&lots=1&buyPrice=1000' localhost:8080/holdings` | 新增後 redirect 的網址變成 `/holdings;jsessionid=...`，session 代號跑到網址上，複製網址給別人會一起帶出去 | 第 2 題 commit（設定 `server.servlet.session.tracking-modes=cookie`） |
| 2 | 新增表單代號欄打全形數字 | 代號填 `２３３０`（中文輸入法常見）按新增 | 顯示「股票代號格式不對」，但使用者看起來填的就是 2330 | 第 2 題 commit（代號先做 NFKC 正規化轉半形） |
| 3 | 操作完看 `data/` 資料夾，多了 `holdings.trace.db` | 啟動後打開清單頁，再看 `data/holdings.trace.db` | 每讀一筆持股，H2 就記一筆 `Column "buyPrice" not found`：JdbcClient 預設對應先找駝峰欄位名稱，找不到才找 `buy_price`。功能正常但 trace 檔會一直長大 | 第 2 題 commit（改用自己寫的 RowMapper） |
