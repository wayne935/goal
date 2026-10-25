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

## 隊友副本（2026-10-25，build 7）
新增 4 張表與 3 個函式（`sync/party-setup.sql`）。設計原則：**隊友讀不到彼此的 `goal_events` / `goal_meta`**，也沒有用 security definer 的 view 繞過 RLS；每個人的網頁自己算好每日摘要寫進 `goal_party_days`，隊友只讀得到這張表的 `active`、`cats`，以及本人選擇分享時的 `xp`、`streak`。

| 表 | 誰能讀 | 誰能寫 |
|---|---|---|
| `goal_parties` | 自己隊伍的成員 | 沒有人能直接寫，只能透過 `goal_create_party()` |
| `goal_party_members` | 同隊的成員 | 加入只能透過 `goal_join_party()`；只能刪除（退出）自己那一列、只能改自己的 `display_name`（欄位層級權限，不能換隊） |
| `goal_party_days` | 自己，以及**目前**同隊的人（退出後立刻讀不到） | 只能寫自己的列；日期限制在最近 61 天內；`cats` 只能是 5 個分類、`xp` 0～5000 |
| `goal_cheers` | 送出或收到的本人 | 只能用自己的名義、送給目前同隊的人、日期在今天前後一天；同一天同一人只能一次；不能改、不能刪 |

- 函式都是 `security definer` + `set search_path = ''`，只 grant 給 `authenticated`；`anon` 對所有新表和函式都沒有權限（Supabase 預設會給 anon 全部權限，SQL 裡先全部 revoke 再只開需要的）。
- 邀請碼 8 碼、32 個字元（約 1 兆種組合），由 `gen_random_uuid()` 的隨機位元產生。
- 已在本機 PostgreSQL 16 模擬 Supabase（`anon` / `authenticated` 角色、`auth.uid()`、預設權限）實測 50 多項（`sync/test/run-party-test.sh` 可以重跑）：未登入什麼都讀不到也呼叫不了函式；不同隊的人讀不到彼此的隊伍、成員、摘要、加油；不能把自己塞進別隊、不能換隊、不能直接建立或修改隊伍；不能改或刪別人的摘要；不能替別人加油、不能加油給別隊或自己、不能補很久以前的日期；第 5 個人加入會被擋；最後一人退出時隊伍會刪除。
- 網頁端：隊友的名字、隊名都是別人可以控制的內容，一律經過 `validPartyMember()` / `validPartyInfo()`（長度、型別）與 `esc()`；`cats` 只留 `CATS` 自己有的 key（用 `Object.hasOwn`，擋掉 `constructor` 之類）。已用 `<img src=x onerror=alert(1)>` 之類的名字實測不會執行。
- 收到的加油與共同 Boss 獎勵是本機產生的事件（固定 id，不會重複入帳），`normalize()` 不接受這兩種類型，不能從 inbox 或貼上偽造。
- 剩下的風險（可接受）：同隊的人看得到你設定的顯示名稱與每日摘要；你分享 XP 時，隊友看得到每天的 XP。隊友可以在自己的裝置上竄改自己的摘要（例如假裝每天都有紀錄）來多打共同 Boss，這只影響隊伍內的遊戲公平，不影響任何人的資料。

## 之後維護
- 新增任何會顯示在畫面上的欄位，一律用 `esc()`；新增任何外部資料入口，先經過 `validEvent` / `validHabits` / `validProblems` 等檢查函式。
- 不要把 Supabase 的 secret key 或 service_role key 放進程式碼；網頁只能用 publishable key。
- 新增資料表時，一定要開 Row Level Security，並加上「只能存取自己資料」的 policy。
