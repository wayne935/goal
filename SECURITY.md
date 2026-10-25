# Goal 資安審查

> 日期：2026-10-22　｜　版本：網頁 build 3　｜　審查者：Claude
> 範圍：公開網頁（wayne935.github.io/goal）、Supabase 後端、Android App 與小工具、自動更新流程、本機 start.bat、GitHub repo

## 結論
- **沒有 SQL injection。** 程式不自己組 SQL，所有資料庫存取都透過 Supabase 的 PostgREST（參數化查詢），而且有 Row Level Security。用公開金鑰實測三種注入手法，全部被擋。
- **Supabase 設定正確。** 未登入的人讀不到也寫不進任何資料表；公開註冊、匿名登入、GraphQL 都是關閉的；不能列出資料表結構。
- **找到 1 個中風險、5 個低風險問題，都已修正**（網頁 build 3 已發佈，App 會自動更新）。其中 1 項原生端修正要下次重新建置 App 才生效。

## 測試方式
- 只用公開 repo 裡拿得到的東西（Supabase URL + publishable key）從外部攻擊 Supabase。
- 逐行檢查網頁所有寫入畫面的地方（innerHTML），以及所有外部資料的入口：inbox 檔案、貼上 AI 判定、匯入備份、雲端同步、題單匯入、小工具佇列。
- 用惡意資料從每個入口攻擊網頁（XSS、prototype pollution、格式錯誤、超大數值），比較修正前後。
- 檢查 Android 程式的 intent、receiver、token 儲存、備份、WebView 設定，以及自動更新的下載與路徑處理。
- 掃描 GitHub repo 全部歷史，確認沒有密鑰或個人資料。

## 發現與處理

| # | 風險 | 問題 | 狀態 |
|---|---|---|---|
| 1 | 中 | **紀錄的圖示欄位沒有跳脫，可以塞入惡意 HTML（stored XSS）。** 來源可以是 inbox 檔案、貼上的 JSON、匯入的備份或雲端資料。成功的話能讀到登入 token；在筆電上若已連結 Goal 資料夾，還能改寫資料夾裡的檔案（包括 start.bat）。已實測修正前會執行、修正後不會。 | ✅ build 3 修正 |
| 2 | 低 | 題單的題號欄位沒有跳脫（只有雲端或匯入資料被竄改時才會發生）。 | ✅ build 3 修正 |
| 3 | 低 | 外部資料沒有檢查格式：格式錯誤會讓頁面當掉、可塞 `__proto__` 之類的特殊 key、自訂紀錄的 XP 上限可被繞過。現在所有入口都會檢查型別、長度、數值範圍，不合格的資料直接丟掉。 | ✅ build 3 修正 |
| 4 | 低 | 沒有 Content Security Policy，也沒有防止被別的網站用 iframe 嵌入。現在加了 CSP：資料只能送到 Supabase，不能送到其他網站；並拒絕在 iframe 中開啟。 | ✅ build 3 修正 |
| 5 | 低 | start.bat 用的本機伺服器會把整個 Goal 資料夾（包括 `data/` 紀錄）提供給 localhost，惡意網站有機會用 DNS rebinding 讀到。改用 `serve.py`：只提供網頁需要的檔案，並檢查 Host 標頭。 | ✅ 已修正 |
| 6 | 低 | Android：`openUrl` 可以開任何網址或 intent；App 允許 Google 自動備份（備份會包含登入 token）；debug 版的 WebView 可以被 USB 偵錯檢視。改成只開 https 連結、關閉備份、關閉 WebView 偵錯。 | ✅ 程式已修正，🙋 下次重新建置 App 才生效 |
| 7 | 資訊 | 自動更新信任的是 GitHub 帳號：誰能推到 `wayne935/goal`，誰就能更新你手機上的 App 網頁。 | 🙋 建議開啟 GitHub 兩步驟驗證 |
| 8 | 資訊 | 知道你 Email 的人可以觸發 Supabase 寄登入連結或重設密碼信（只會造成垃圾信，拿不到帳號）。Email 已從公開程式碼移除。 | 可接受 |
| 9 | 資訊 | 公開金鑰與 Supabase 網址出現在公開程式碼中，這是設計如此；錯誤訊息會透露資料表名稱。 | 可接受 |

## 建議你做的事
1. **GitHub 開啟兩步驟驗證**（Settings → Password and authentication → Two-factor authentication）。這是保護自動更新管道最重要的一步。
2. **Supabase 帳號用夠長且沒在別處用過的密碼**（建議 16 字元以上）。
3. **不開發時把手機的 USB 偵錯關掉**。用 Android Studio 裝的是 debug 版，接上電腦且手機解鎖時可以讀到 App 資料。
4. **方便時重新建置一次 App**（`build-android.bat` → ▶ Run），讓第 6 項的原生修正生效。不急，網頁端的修正已經自動送到手機。
5. （選擇性）**自動更新加上數位簽章**：發佈時用一把只存在你電腦上的私鑰簽名，App 驗證通過才更新。這樣就算 GitHub 帳號被盜，也推不了惡意更新。代價是要再重新建置一次 App，而且發佈時需要能讀到那把私鑰。

## 之後維護
- 新增任何會顯示在畫面上的欄位，一律用 `esc()`；新增任何外部資料入口，先經過 `validEvent` / `validHabits` / `validProblems` 等檢查函式。
- 不要把 Supabase 的 secret key 或 service_role key 放進程式碼；網頁只能用 publishable key。
- 新增資料表時，一定要開 Row Level Security，並加上「只能存取自己資料」的 policy。
