-- 第 3 題：新增持股時向官方查到的股票名稱與市場別（TWSE 上市、TPEX 上櫃）
ALTER TABLE holding ADD COLUMN name VARCHAR(50);
ALTER TABLE holding ADD COLUMN market VARCHAR(4);
