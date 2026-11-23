# 持股記帳

記下自己買了哪些台股，系統用證交所、櫃買中心公布的收盤價，算出「如果現在全部賣掉，扣掉手續費和證交稅後淨賺或淨虧多少」。

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

## 時間

首頁的「目前時間」一律是台北時間（`Asia/Taipei`），跟主機的時區設定無關。
