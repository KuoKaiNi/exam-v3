-- 收盤價快取：重啟伺服器後不用等重查官方就能顯示上次拿到的收盤價。
-- source 記錄是從哪個主機查到的；換了行情主機（例如換成測試伺服器）時，舊主機的資料不沿用。
CREATE TABLE quote_cache (
    code         VARCHAR(6)     NOT NULL PRIMARY KEY,
    name         VARCHAR(50),
    market       VARCHAR(4)     NOT NULL,
    price_date   DATE           NOT NULL,
    close_price  DECIMAL(12, 2) NOT NULL,
    source       VARCHAR(200)   NOT NULL,
    fetched_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
