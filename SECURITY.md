# 相依套件的已知弱點

掃描日期：2026-11-23。掃的是 `./mvnw package` 產出的 `target/holdings-1.0.0.jar`（也就是實際上線的執行檔，內含 47 個執行期相依套件；測試用的套件不會進 jar，沒列在這裡）。

| 工具 | 版本 | 指令 |
|---|---|---|
| grype | 0.114.0 | `grype target/holdings-1.0.0.jar` |
| trivy | 0.71.0 | `trivy rootfs --scanners vuln target/holdings-1.0.0.jar` |
| osv-scanner | 2.3.8 | `osv-scanner scan source -L pom.xml` |

osv-scanner 要向 Maven Central 解析 `pom.xml` 的相依關係，掃描當下被 Maven Central 限流（HTTP 429），第二次跑回報 0 筆，連 grype、trivy 都抓到的 spring-webmvc 也沒列出，**這次的 osv-scanner 結果不採用**。以下以 grype、trivy 為準。

## 已處理：覆寫版本

Spring Boot 3.5.16 預設帶的版本有 51 筆已知弱點（grype）。下面三個套件在同一個小版號系列裡有修補版，在 `pom.xml` 用屬性覆寫，不換大版本、API 相容，覆寫後 56 個自動測試與手動操作都通過：

| 套件 | 原本 | 改成 | 修掉的弱點 |
|---|---|---|---|
| `org.apache.tomcat.embed:tomcat-embed-core`、`tomcat-embed-websocket` | 10.1.55 | 10.1.62（`tomcat.version`） | 37 筆，例如 CVE-2026-86350、CVE-2026-65905、CVE-2026-101296、GHSA-9xv2-5v5q-p794 |
| `com.fasterxml.jackson.core:jackson-core`、`jackson-databind` | 2.21.4 | 2.21.8（`jackson-bom.version`） | 11 筆，例如 GHSA-p6pp-m3f8-5c89、GHSA-q4xh-88c3-wmh7、GHSA-cxp5-3px4-pw24 |
| `org.apache.logging.log4j:log4j-api` | 2.24.3 | 2.25.5（`log4j2.version`） | GHSA-qv9r-c865-cp47 |

## 交卷時還在的弱點

| 弱點 | 套件 | 嚴重度 | 會不會被打到 | 為什麼 | 怎麼處理 |
|---|---|---|---|---|---|
| CVE-2026-47884（GHSA-pc63-qcmh-9cmg）XsltView 路徑限制不當，可能 SSRF、RCE | `spring-webmvc` 6.2.19 | Critical | 不會 | 只有用到 `XsltView`、又有 `/**` 對應到畫面、且沒明確指定 view 名稱時才會中。本專案畫面只用 Thymeleaf，沒有 XSLT，沒有 `/**` 對應 | 暫不處理。Spring Framework 6.2 系列沒有公開的修補版（修補只在 7.0.9），要修得升 Spring Boot 4。Part B 若要換大版本時一起升 |
| CVE-2026-47890（GHSA-j9f9-w8pj-32f8）SSE 搭配 view fragment 時資料流錯亂 | `spring-webmvc` 6.2.19 | Critical（工具標示） | 不會 | 只影響用 Server-Sent Events 回傳 view fragment 的程式。本專案沒有 SSE、沒有 fragment 串流 | 暫不處理，理由同上 |
| CVE-2026-133325 Tomcat 叢集在 TLS 1.3＋TLS-PSK 下，未驗證的節點可以加入叢集 | `tomcat-embed-core` 10.1.62 | Critical（grype） | 不會 | 只影響有開 Tomcat 叢集（`catalina-ha`、`tribes`）的情況。Spring Boot 內嵌 Tomcat 不含叢集模組（jar 裡確認沒有），也沒有叢集設定。trivy 的資料庫還沒收錄這筆 | 暫不處理。修補版 10.1.63 交卷時 Maven Central 還沒有，出來後把 `tomcat.version` 改成 10.1.63 |

## 其他跟資安有關的設定

- H2 網頁主控台（`/h2-console`）沒開（Spring Boot 預設關閉），資料庫檔只在本機 `data/`。
- 密碼、token 沒有寫進 repo。資料庫帳號密碼可用 `DB_USER`、`DB_PASSWORD` 環境變數給。
- 官方行情網址只有主機部分可以設定，代號經過格式檢查（4～6 碼英數字）才會組進網址，不會被拿來打別的路徑。
