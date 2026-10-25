# 接下來要實作的事

> 更新：2026-10-25　｜　目前網頁 build 5，原生 NATIVE_VERSION 3（`version.json` 的 minNative 2）
> 先讀 `CLAUDE.md`（架構、發佈規則、測試方法）。設計理由與研究依據在 `PLAN.md` 第 8 節。

## 目前狀態
- 階段 1～3 完成：打卡、LeetCode 判題、徽章、商店、題單、每日任務、每週回顧、PWA、Supabase 同步、Android App 與小工具。
- **8.1 惡習 Boss 完成（build 5，2026-10-23）**：「惡習」分頁、`restraint` / `relapse` 事件、`simulateBosses()` 逐日推算血量、打倒動畫、存錢罐、6 個徽章。數值在 `BOSS` 常數。
  - 已知取捨：建立 Boss 的當天就算第一個攻擊日；手機底部分頁列已有 7 個分頁。
- 使用者還沒做的事：重新建置一次 App（讓原生震動與資安修正生效）、正常使用一週後回報數值手感。
- **使用者決定的順序（2026-10-25）：先做第 1 項隊友副本，承諾契約延後。**

## 1. 隊友副本：讓別人看得到你（PLAN 8.3）← 下一個要做
目標：和 1～3 個朋友（實驗室同學、刷題夥伴）組隊，看得到彼此「今天有沒有動」，每週一起打一隻共同 Boss，可以互相加油。
依據見 `PLAN.md` 8.3：Goal 目前只滿足成就與精通動機，社交完全沒有；30% 的人在有人等著時才開始做事。
標 🙋 的地方開始寫程式前先問使用者。

### 1.0 限制與前提（先讀）
- **雲端 session 不能動 Supabase。** 資料表、RLS、函式都寫成 `sync/party-setup.sql`（可重複執行，風格照 `sync/supabase-setup.sql`），由使用者自己到 Supabase → SQL Editor 執行。完成後在回覆裡清楚寫出這一步。
- **網頁要能在 SQL 還沒執行時正常運作**：呼叫隊伍相關的表或函式回 404 / `PGRST202` / `42P01` 時，隊伍區塊只顯示「需要先在 Supabase 執行 `sync/party-setup.sql`」，其他功能完全不受影響。上線順序是使用者先跑 SQL，再合併網頁到 main。
- **公開註冊是關閉的**，朋友要有帳號。🙋 建議做法：使用者在 Supabase 後台（Authentication → Users → Add user → Create new user，勾 Auto Confirm）幫朋友建帳號和臨時密碼；網頁「設定 → 雲端同步」新增「改密碼」（`PUT {SB.url}/auth/v1/user`，body `{ "password": "..." }`）。不要做 Supabase 邀請信流程（要處理網址 hash 裡的 token，比較複雜）。
- 朋友用同一個網址 https://wayne935.github.io/goal/ 或 App，資料靠 RLS 各自隔離。朋友登入後會看到預設習慣（包含 Wayne 的貝斯、西洋棋），可以在設定自己改，這次不用處理。
- 一個人同時只能在一個隊伍；每隊最多 4 人（含自己）。

### 1.1 隱私原則：只分享摘要，在自己裝置上算好才上傳
- 不開放別人讀 `goal_events`、`goal_meta`，**也不要用 security definer 的 view 繞過 RLS 去讀別人的事件**。
- 每個人的網頁自己算出每日摘要，寫進新的 `goal_party_days`；隊友只讀得到這張表裡的欄位。
- 分享程度存在 `settings.partyShare`（記得加進 `validSettings()`）：
  - `'basic'`（預設）：當天有沒有紀錄、做了哪些分類（`cats`）。
  - `'xp'`：再加上當天 XP 與連續天數。
  - 🙋 確認預設值。PLAN 寫「預設只顯示今天有紀錄與分類」，建議照做。
- 改成較低的分享程度時，把已經上傳的 `xp`、`streak` 欄位清成 null。

### 1.2 Supabase 資料表（`sync/party-setup.sql`）
| 表 | 欄位 | 說明 |
|---|---|---|
| `goal_parties` | `id uuid pk default gen_random_uuid()`、`name text`（check 長度 1～30）、`invite_code text unique`、`owner uuid`、`created_at` | 隊伍 |
| `goal_party_members` | `party_id`、`user_id`（pk 兩者）、`display_name text`（check 長度 1～20）、`joined_at`；`user_id` 加 unique（一人一隊） | 成員 |
| `goal_party_days` | `user_id`、`day date`（pk 兩者）、`active bool`、`cats text[]`（check 只能是 code/health/research/hobby/life）、`xp int null`（check 0～5000）、`streak int null`、`updated_at` | 每日摘要 |
| `goal_cheers` | `id uuid pk`、`party_id`、`from_user`、`to_user`、`day date`、`created_at`；unique(`from_user`,`to_user`,`day`)；check `from_user <> to_user` | 加油 |

- 函式一律 `security definer`、`set search_path = ''`、只 grant 給 `authenticated`、revoke `anon` 與 `public`：
  - `goal_my_party() returns uuid`：目前使用者的隊伍 id（RLS 政策用它，避免成員表的政策遞迴）。
  - `goal_create_party(p_name text, p_display_name text) returns table(id uuid, invite_code text)`：已經在隊伍裡就丟錯；邀請碼產生 8 碼、排除 0/O/1/I（`search_path` 是空的，pgcrypto 要寫完整名稱 `extensions.gen_random_bytes`，或用 `gen_random_uuid()` 轉換）。
  - `goal_join_party(p_code text, p_display_name text) returns uuid`：找不到、滿 4 人、已在別隊都丟錯（錯誤訊息用英文代碼，網頁再翻成中文）。
  - 退出隊伍：成員可以刪掉自己那一列；最後一個人退出時刪掉隊伍（trigger 或在函式裡做）。
- RLS 政策：
  - `goal_parties`：只能 select 自己的隊伍（`id = goal_my_party()`）；不開放直接 insert/update/delete（只能透過函式）。
  - `goal_party_members`：select `party_id = goal_my_party()`；delete 只能刪 `user_id = auth.uid()`；update 只能改自己的 `display_name`。
  - `goal_party_days`：insert/update/delete 只能是自己的列；select 自己的，或 `user_id` 和自己在同一隊。**退出隊伍後隊友就讀不到**（政策是即時判斷，不用另外刪資料）。
  - `goal_cheers`：insert 必須 `from_user = auth.uid()`、雙方同隊、`day` 在今天前後一天內；select `to_user = auth.uid() or from_user = auth.uid()`。
- 只保留最近 60 天的 `goal_party_days`：網頁上傳時順便刪掉自己 60 天前的列即可，不用排程。
- 寫完自己檢查一遍：未登入（anon）什麼都讀不到；不同隊的人讀不到彼此；不能把自己加進別隊；不能替別人加油或改別人的摘要。把檢查結果寫進 `SECURITY.md` 新的一節。

### 1.3 網頁：資料流
- 用一個小物件包住所有隊伍 API（例如 `const partyApi = { mine, create, join, leave, rename, pushDays, pullDays, cheer, pullCheers }`），全部走 `sbRest()`（RPC 是 `POST rpc/<函式名>`），並掛到 `window.__goal.partyApi`，測試時可以在 Console 換成假資料。
- 隊伍狀態（隊伍、成員、隊友摘要）放在記憶體的 `party` 物件，另存一份到 localStorage 給離線時顯示；**不放進 `state`，也不放進 `goal_meta`**（它是別人的資料，不是自己的紀錄）。
- `syncNow()` 成功後（有登入且在隊伍裡）：上傳自己最近 8 天的摘要（從 `compute().days` 取：`active = d.any`，`cats = [...d.cats]`，`xp` / `streak` 依分享程度），再拉隊友最近 14 天的摘要與給自己的加油。隊伍的請求失敗不要讓整個同步變成失敗狀態。
- 從雲端拉回來的隊友資料一律先經過 `validPartyMember()` / `validPartyDay()`：名字 `str(…, 20)`、`cats` 只留 `CATS` 有的 key、數字用 `num()` 限範圍；**畫面上所有隊友名字、隊名都要 `esc()`**（這是其他人可以控制的內容）。

### 1.4 每週共同 Boss（純前端推算）
- 週一到週日（用 `weekStart()`）。血量 = 🙋 每位成員 150 × 隊伍人數。
- 傷害：每位成員每天每個有紀錄的分類 10 點（一天最多 50），只用 `cats`，所以 `'basic'` 分享程度也能算。
- 顯示：Boss 血條、每個人本週貢獻的傷害、星期幾到星期日的格子（誰哪天有動）。
- 週日結束（過了下週一的 `dayStartHour`）且已打倒 → 每位成員各自在本機寫一筆 **`party_reward` 事件**：`{ id: 'pr-<partyId>-<週一 dayKey>', type: 'party_reward', ts, party, week, members }`。固定 id 確保同一週只入帳一次；獎勵 🙋 建議 +40 XP、+60 代幣。
  - 這是新的事件類型：要改 `compute()`、`validEvent()`、`eventTitle()` / `eventIcon()`；`normalize()` 不接受這個類型（不能由 inbox 或貼上產生）。
  - 寫入時機：打開網頁、同步完成後檢查上一週。沒打倒就不寫，也不用懲罰。
- 本週剛打倒時播一次慶祝（可以沿用 `type: 'boss'` 的動畫，或加 `type: 'party'`），「看過了沒」記在 localStorage（例如 `goal.partySeen`）。

### 1.5 加油
- 隊友卡片上的「加油」按鈕：每人每天對同一個隊友 1 次（資料庫 unique 擋）。
- 收到的加油在本機變成 **`cheer` 事件**：`{ id: 'ch-<cheer id>', type: 'cheer', ts, from: '<名字>' }`，固定 id 避免重複入帳。🙋 建議每次 +5 代幣、不給 XP，每天最多 3 次計分（超過只記錄，像習慣的每日上限）。
- 送出加油的人：toast「已幫 ○○ 加油」；收到的人：同步後 toast「○○ 幫你加油了 +5 代幣」。

### 1.6 介面（不要新增分頁，目前已有 7 個）
- **「今天」分頁**加一個「隊伍」面板（放在今日任務下面）：
  - 沒登入雲端：一行說明「登入雲端同步後可以和朋友組隊」。
  - 沒有隊伍：「建立隊伍」或「輸入邀請碼加入」，兩個都要填自己的顯示名稱。
  - 有隊伍：共同 Boss 血條與倒數、每個隊友一張小卡（名字、今天有沒有紀錄、分類色點、分享 `xp` 時顯示 XP 與連續天數、加油按鈕）、自己本週的傷害。
- **「設定」分頁**加「隊伍」區塊：隊名、邀請碼（可複製）、成員列表、改顯示名稱、分享程度、退出隊伍（要用 toast 確認或二次點擊，因為會失去本週進度）。
- 手機寬度（720px 以下）要檢查排版。

### 1.7 徽章（建議，cat 用 `life`）
第一次組隊、打倒第一隻共同 Boss（傳說級）、共同 Boss 連續 4 週打倒、送出 10 次加油、收到 10 次加油。

### 1.8 測試
- 沒辦法在雲端連到真的資料庫，所以：
  1. 用 `window.__goal.partyApi` 換成假資料（2～3 個隊友、一週的摘要、幾筆加油），測畫面、共同 Boss 計算、`party_reward` 與 `cheer` 只入帳一次、惡意名字（`<img src=x onerror=alert(1)>`）被跳脫。
  2. 模擬 SQL 還沒執行（API 回 404）時，隊伍區塊顯示提示、其他功能正常。
  3. 照 `CLAUDE.md` 的方法檢查每個分頁與舊資料。
- 真正的端對端測試由使用者做：跑 SQL → 建一個測試帳號 → 兩個瀏覽器（一般 + 無痕）各登入一個帳號 → 組隊、打卡、加油。把這個步驟寫進回覆。

### 1.9 完成時
- `APP_BUILD` 與 `version.json` 改成 6（只改網頁，不用重新建置 App；小工具顯示隊伍是之後的事）。
- 更新 `ROADMAP.md`（功能表與執行紀錄）、`PLAN.md` 8.3（標記完成與實際數值）、`README.md`（怎麼組隊、怎麼幫朋友開帳號）、`SECURITY.md`（新的資料表與政策）、`CLAUDE.md`（新的事件類型 `party_reward`、`cheer` 與 `partyApi`）。
- 在分支上完成並推上去；**合併到 main 之前先讓使用者執行 SQL**。回覆裡列出改了哪些檔案、使用者要做的步驟（執行 SQL、幫朋友建帳號、端對端測試）。

## 2. 承諾契約（PLAN 8.2）← 隊友副本之後
拿代幣下注本週目標，每週一（月初、生日）跳出「開局」畫面。以下是建議做法，**標 🙋 的地方開始前先問使用者**。

**資料（沿用 event sourcing）**
- `contract_open` 事件：`{ id, type: 'contract_open', ts, week: '<該週週一的 dayKey>', goal: { kind, habit?, boss?, target }, stake }`
  - `kind`：`habit`（某習慣本週做 N 次）、`lc`（本週解 N 題）、`clean`（某個惡習 Boss 本週犯 ≤ N 次）。
  - 每週最多一份（或兩份）契約；押金上限是目前餘額。
- 不需要 `contract_settle` 事件：在 `compute()` 裡推算。開約當下押金就從餘額扣掉（像 `redeem`）；達成的那一天把 `stake × 倍率` 記到當天的 coins（像每週目標的 bonus）；期限過了還沒達成就是沒收（不用做任何事）。
- 緩衝日：期限 = 該週週日 + 1 天（下週一的 `dayStartHour` 前都還算）。
- 🙋 倍率：PLAN 寫 1.5～2 倍。建議 1.5 倍，或依目標難度 1.5 / 2 倍。
- 🙋 契約能不能刪：event sourcing 下刪掉 `contract_open` 等於退回押金，可以用來賴帳。建議開約 1 小時後就不顯示刪除鈕（`logItem` 裡判斷），或刪除時押金照樣沒收。

**開局畫面（新開始效應）**
- 「今天」分頁最上面一個面板：本週還沒有契約、而且今天是週一 / 每月 1 號 / 生日時顯示（其他天可以收合成一行「本週還沒開局」）。
- 🙋 生日：`settings.birthday`（`MM-DD`），在設定頁的「偏好」新增欄位；記得加進 `validSettings()`。
- 進行中的契約顯示進度條和倒數，例如「運動 2 / 3，還剩 3 天，達成拿回 150」。

**連續天數保護卡**
- 商店固定商品（不在 `state.rewards` 裡，避免被刪掉），🙋 價格建議 150 代幣，最多持有 2 張。
- 購買是 `freeze_buy` 事件；使用不用另外記：`compute()` 算連續天數時，遇到空白的一天、而且當時手上有卡（那天之前買的、還沒用掉），就自動用掉一張補上。要改 `currentRun` / `longestRun` 的呼叫方式（目前只吃一個 Set）。
- 被保護卡補上的那天在熱力圖上用不同樣式標示。

**誘惑綁定（可以最後做或延後）**
- `state.bundles`：`{ id, name, icon, habit }`，例如「這個 podcast 只在跑步時聽」。打卡那個習慣後，toast 提醒「現在可以聽 🎧 ○○○」。走 `goal_meta` 同步。

**徽章（建議）**：第一份契約、連續 4 週達成契約、押注 500 以上並達成、第一次用到保護卡。

**可以和隊伍結合**：契約指定一位隊友當見證人，隊友看得到契約進度（需要在 `goal_party_days` 或另一張表加欄位）。

**完成時**：`APP_BUILD` 與 `version.json` 再 +1，更新 `ROADMAP.md`、`PLAN.md` 8.2、`README.md`。只改網頁，不用重新建置 App。

## 3. 之後的候選（依建議順序）
1. **提醒通知**（ROADMAP 階段 2 第 5 項）：先做網頁開著時的本機提醒（例如晚上 10 點還沒運動、Boss 今天還沒忍住、隊友都動了只剩你）。真正的手機推播要用 Android 原生（WorkManager + 通知），要 bump `NATIVE_VERSION` 並重新建置。
2. **小工具加「忍住了」按鈕與隊伍狀態**：原生改動（`GoalWidgetProvider.java`、`goal_widget.xml`、`GoalStore` 佇列支援 `restraint` 事件），要 bump `NATIVE_VERSION`、`minNative`，請使用者重新建置。
3. **自動判定半夜滑手機**：Android `UsageStatsManager`，需要使用者授權「使用情況存取權」，原生改動。
4. **LeetCode Chrome 擴充功能**（階段 4，可選）：偵測 Accepted，送出 `leetcode_submitted: true` 的事件。
5. **數值調整**：等使用者回報一週的使用手感，不要自己猜。
