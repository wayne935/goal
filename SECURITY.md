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
### 隊伍第二輪（build 20，`sync/party-v2.sql`）
| 資料表 | 誰讀得到 | 誰能寫 |
|---|---|---|
| `goal_cheers.kind` | 同上（送的人和收到的人） | 只能是 6 種表情之一 |
| `goal_nudges` | 送的人和被戳的人 | 只能用自己的名義、戳同隊的人、日期在今天前後一天；同一人一天 1 次；不能改不能刪 |
| `goal_gifts` | 送的人和收到的人 | 同上；每人每天只能送 1 份；`item` 只能是 5 種；不能改不能刪 |
| `goal_witness` | 簽約的人和見證人 | 簽約的人建立（見證人要同隊、契約是最近的）、只能更新 `count` / `status`、可以刪；`verdict` 只能由見證人透過 `goal_witness_verdict()` 改一次，契約那週週一起 10 天內 |
| `goal_party_messages` | **目前**同隊的人 | 只能用自己的名義發到自己的隊伍（寄件人、時間由資料庫填）；1～200 字；不能改，只能刪自己的 |

- 已在本機 PostgreSQL 16 實測（`sync/test/run-party-v2-test.sh`）：每一項應該被擋的都看到 ERROR 或 0 筆，包括假冒別人、送給別隊、改禮物、簽約的人自己改見證結果、見證人按兩次、過期見證、別隊讀聊天、退出後讀聊天。
- 網頁收到的資料一律經過 `validNudge()`、`validGift()`、`validWitness()`、`validMessage()`；聊天內容、契約說明、名字都經過 `esc()`（測過 `<img onerror>`、`<script>`）。
- 剩下的風險（可接受）：禮物的代幣是在送禮的人本機扣（`gift_send` 事件不能刪），收到的人看到雲端那一列就入帳。同隊的人如果直接呼叫 API，可以不扣自己的代幣就送禮（一天最多 1 份、最多 100 代幣或一張保護卡、1 小時雙倍 XP），只影響隊伍內的公平。聊天沒有頻率限制，隊伍最多 4 人、都是認識的人。

### 一起專注（build 21，`sync/party-v3.sql`）
- `goal_focus_rooms`：只有同隊看得到；沒有任何直接寫入權限，只能透過函式開房（同隊同時一間、15～120 分）、開始（只有開房的人、至少 2 人、時間由資料庫填）、取消（只有開房的人、還沒開始）。
- `goal_focus_members`：同隊看得到；只能加入自己、只能加入還沒開始的房間；只能改自己的 `state`（6 種之一），不能改任務名稱；開始後不能退出。
- 已在本機 PostgreSQL 16 實測（`sync/test/run-party-v3-test.sh`）：別隊看不到也加入不了、不能替別人加入或改別人的狀態、不能直接改房間的開始時間、只有開房的人能開始或取消、同隊不能同時開兩間、過期的房不能開始或加入、退出隊伍後看不到房間。
- 剩下的風險（可接受）：狀態是每個人自己回報的，同隊的人可以謊報「撐完了」來拿團隊獎勵；自己的鎖機獎勵仍然是原生端寫的事件，不受影響。

### 情侶隊與交換日記（build 23，`sync/party-v4.sql`）
| 資料表 / 欄位 | 誰讀得到 | 誰能寫 |
|---|---|---|
| `goal_parties.kind` / `since` | 同隊的人 | 只能透過 `goal_create_party_kind()`、`goal_set_party_kind()`（超過 2 人不能改成情侶隊）、`goal_set_party_since()`（1950 年到明天） |
| `goal_party_days.mood` | 同上（同隊的人） | 自己；網頁只在情侶隊、設定沒關掉時上傳 |
| `goal_exchange` | 同隊的人 | 只能用自己的名義分享到自己的隊伍；可以改、刪自己的；不能換作者、不能改建立時間、不能搬到別隊；標題 60 字、內文 1～5000 字 |
| `goal_exchange_replies` | 看得到那篇日記的人 | 只能用自己的名義回覆同隊的日記；表情只能是 6 種之一；不能改，只能刪自己的 |
| `goal_daily_answers` | 自己的隨時看得到；同隊的人的要自己那天也答了才看得到（`goal_answered()`） | 只能用自己的名義、日期在今天前後一天；一天一則；不能改不能刪 |

- 離開隊伍時，trigger 會刪掉自己在那個隊伍分享的交換日記（連同底下的回覆）、自己的回覆和每日一問的答案；最後一個人離開時隊伍和所有資料一起刪除。
- **分享出去的那一份是明文**：Supabase 專案擁有者（就是 Wayne 自己）在後台看得到，跟隊伍聊天一樣；沒分享的日記仍然只有密文。分享前寫日記畫面和日記分頁都有說明，可以隨時收回（真的刪掉那一列）。
- 網頁收到的資料一律經過 `validExchange()`、`validReply()`、`validAnswer()`、`validPartyKind()`，內容和名字都經過 `esc()`（測過 `<script>`、`<img onerror>`）。對方的日記、回覆、答案只放在記憶體，不寫進 localStorage。
- 已在本機 PostgreSQL 16 實測（`sync/test/run-party-v4-test.sh`）：第 3 個人加入情侶隊、超過 2 人改成情侶隊、未來的紀念日、別隊讀寫交換日記、冒名分享或回覆、改別人的日記、upsert 蓋掉別人的、換隊、沒回答就讀對方的答案、改答案、離開後還留著資料，都被擋住。
- 剩下的風險（可接受）：隊上兩個人都能改隊伍類型和紀念日；回覆沒有頻率限制（只有 2 個人）。

- 剩下的風險（可接受，第一輪）：同隊的人看得到你設定的顯示名稱與每日摘要；你分享 XP 時，隊友看得到每天的 XP。隊友可以在自己的裝置上竄改自己的摘要（例如假裝每天都有紀錄）來多打共同 Boss，這只影響隊伍內的遊戲公平，不影響任何人的資料。

### 影劇心得與一起看的片（build 24，`sync/tmdb-function.ts`、`sync/party-v5.sql`）
- **TMDB 金鑰不在公開的網頁或 repo 裡**：放在 Supabase Edge Function 的 secret `TMDB_KEY`。函式只接受登入的人（用請求帶的 token 問 Supabase `/auth/v1/user`，認得才放行；所以 Verify JWT 可以關掉，新舊金鑰都適用），只轉發白名單裡的路徑（搜尋、電影、影集、某一季）和 4 個參數，固定 `include_adult=false`；TMDB 回錯誤時回 502。已用 Node 模擬 Deno 測 16 項（`sync/test/tmdb-function-test.mjs`）：沒登入、假 token、亂填路徑（`/account`、`../`、帶查詢字串）、POST、沒存金鑰、兩種金鑰格式。
- 剩下的風險（可接受）：任何有 Goal 帳號的人（就是你們兩個）都能用這個函式查 TMDB，沒有頻率限制（TMDB 本身有限流）；函式的 CORS 是 `*`，但沒有登入 token 一樣不能用。
- 網頁收到的 TMDB 資料一律經過 `validTmdbItem()` / `validTmdbSeasons()`（只收電影和影集、id 是正整數、片名截長度）；海報只收 `/xxxx.jpg` 這種路徑，再由網頁自己組成 `https://image.tmdb.org/t/p/...`（CSP 的 `img-src` 只多開這個網域，`connect-src` 不變，因為代轉在 Supabase 上）。測過 `<img onerror>` 片名、`javascript:` 海報、person 結果。

| 資料表 | 誰讀得到 | 誰能寫 |
|---|---|---|
| `goal_watch_together` | 自己的隨時看得到；同隊的人的，要自己也寫了同一部（同一季）才看得到（`goal_watched()`） | 只能用自己的名義寫到自己的隊伍；可以改（upsert）、刪自己的；不能換人、換隊、改時間；片名 100 字、心得 3000 字、分數 1～10、海報要符合路徑格式 |
| `goal_watch_titles()` | 同隊的人：只有片名、海報、日期，**沒有分數和心得** | （函式，唯讀） |
| `goal_watchlist` | 同隊的人 | 只能用自己的名義加到自己的隊伍、同一部不能重複；不能改；同隊的人都可以刪 |

- 離開隊伍時，trigger 刪掉自己在那個隊伍的「一起看的」心得；片單留給還在隊上的人；最後一個人離開時全部刪除。
- 心得是明文（跟交換日記一樣，Supabase 專案擁有者在後台看得到）；自己的影劇紀錄存在 `goal_events`，跟其他紀錄一樣。
- 網頁收到的資料一律經過 `validWatchRow()`、`validWatchTitle()`、`validWatchItem()`，片名、心得、名字都 `esc()`；對方的心得只放在記憶體，不寫進 localStorage。
- 已在本機 PostgreSQL 16 實測（`sync/test/run-party-v5-test.sh`，65 項）：還沒寫的人讀不到對方的分數和心得（直接查表 0 筆、函式沒有分數欄位）、別隊和未登入讀不到、冒名、寫到別隊、壞海報、分數超出範圍、未來日期、upsert 蓋掉別人的、改別人的、換隊換人、片單重複、別隊刪片單、離開隊伍後的清除。
- 剩下的風險（可接受）：已經寫過的人可以把自己的心得改成空的再看對方的（只是兩個人之間的事）。

## 換帳號登入會帶走上一個人的紀錄（2026-10-25，build 8 修正）
- **問題**：瀏覽器裡的紀錄（localStorage `goal.v1`）沒有記是哪個帳號的。同一個瀏覽器先用 A 登入、再改用 B 登入時，同步會把 A 留在本機的全部紀錄當成 B 的上傳到 B 的雲端；B 登入的那台電腦有連結 Goal 資料夾時，也會讀寫 A 的 `data/goal-data.json`。A 雲端上的資料沒有外洩到別的帳號以外的地方（RLS 正常），但 B 拿到了 A 的完整紀錄副本。在 Wayne 用測試帳號登入時發現。
- **修正**：本機紀錄綁定帳號（`state.owner`）。換帳號登入時，目前的紀錄先收進 `goal.v1@<帳號 id>`，新帳號從自己的雲端紀錄開始；換回來時再拿回來。`syncNow()` 多一道檢查：本機紀錄不是目前登入的帳號就不同步。資料夾檔案記錄擁有者，別的帳號登入時不讀、不寫、不收 inbox。build 8 以前、沒有綁定帳號的紀錄：開啟時已經登入的話綁給那個帳號並重新完整同步；登出後換另一個 Email 登入時會先跳出確認。
- **已經被複製的資料**：到 Supabase → Authentication → Users 刪掉那個測試帳號（`goal_events`、`goal_meta` 和隊伍資料會跟著刪除），需要的話再重新建一個。
- 已在瀏覽器用模擬的 Supabase（兩個帳號各自的雲端資料）實測：A → B → A → B 來回切換、舊版資料、全新裝置第一次登入，雲端都沒有混到別人的紀錄。

## 之後維護
- 新增任何會顯示在畫面上的欄位，一律用 `esc()`；新增任何外部資料入口，先經過 `validEvent` / `validHabits` / `validProblems` 等檢查函式。
- 不要把 Supabase 的 secret key 或 service_role key 放進程式碼；網頁只能用 publishable key。
- 新增資料表時，一定要開 Row Level Security，並加上「只能存取自己資料」的 policy。

## build 9（2026-11-02）：鎖手機功能
- **無障礙服務（Goal 專注守護）**只接收「視窗切換」事件（`typeWindowStateChanged`），`canRetrieveWindowContent=false`，讀不到畫面上的文字或輸入內容；只用前景 App 的套件名稱判斷要不要蓋鎖定畫面。
- 專注、宵禁、放行事件由原生端產生，網頁用 `validEvent()` 檢查格式後才入帳；`normalize()`（inbox、貼上、AI）不接受這兩種事件，避免偽造「專注完成」。顯示的 App 名稱、原因都經過 `esc()`。
- 鎖手機設定（`guardConfig`）來自網頁，所以 **SECURITY 第 7 項（誰能推到 repo 誰就能改你手機上的網頁）現在也代表能改鎖機設定**。但緊急解鎖、電話、緊急求救、鬧鐘是原生程式寫死的，網頁無法關掉；最壞的情況是被設成鎖住很多 App，隨時可以緊急解鎖或關閉無障礙服務。建議開啟 GitHub 兩步驟驗證。
- Health Connect 只申請讀取（步數、運動、睡眠），只在打開 App 時讀，不申請背景讀取；轉成的事件只存「達標了」與時長，不存原始健康資料。
- 新增的 receiver、activity、service 都是 `exported="false"`（Health Connect 要求的權限說明 alias 除外，它只能由系統以 `START_VIEW_PERMISSION_USAGE` 權限開啟，打開的是 App 首頁）。

## 日記加密（2026-11-25，build 22）
- 日記的標題和內文在裝置上加密後才存進 `state.events`、同步到 `goal_events`：Web Crypto 的 AES-GCM（256 位元、每篇隨機 12 bytes IV、事件 id 當附加資料，密文不能搬到別筆事件）。明文只有日期、心情、標籤、字數（`compute()` 算分用）。
- 日記金鑰是隨機產生的；用日記密碼（PBKDF2-SHA256 60 萬次）和 12 字備用碼（約 60 bits）各包一份放在 `state.jkey`，跟著 `goal_meta` 同步。改密碼只重包，不用重新加密日記。這台裝置把**不能匯出**的金鑰存進 IndexedDB，換帳號用不同的位置（`<帳號 id>:<kid>`）。
- Supabase 後台（專案擁有者）和任何拿到資料庫或備份檔的人都只看得到密文；離線猜密碼要對每個猜測跑 60 萬次 PBKDF2，所以日記密碼還是要夠長。
- 雲端和備份檔進來的 `journal` 事件一律經過 `validEvent()`（標籤經過 `cleanTag()`、密文長度上限），`jkey` 經過 `validJkey()`；解密出來的文字一律 `esc()` 或 `textContent`。`normalize()` 不接受 `journal`（inbox / AI 不能寫日記）。
- 剩下的風險（可接受）：草稿以明文存在這台裝置的 localStorage；能打開這台已解鎖裝置的人看得到日記（可以在日記分頁「在這台裝置上鎖起來」）；忘記密碼又弄丟備用碼，日記就讀不回來。
