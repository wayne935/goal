# Goal 成就板：給 Claude 的專案說明

自我鼓勵的成就系統：打卡、LeetCode 判題、XP / 等級 / 代幣 / 徽章、獎勵商店、惡習 Boss，網頁 + Android App + 桌面小工具，Supabase 雲端同步。
使用者是 Wayne（寫 LeetCode、做 LLM 研究、練貝斯、學西洋棋），介面全部是繁體中文。

**接下來要做什麼：看 `NEXT.md`。** 功能清單與進度在 `ROADMAP.md`，設計理由與數值在 `PLAN.md`，資安注意事項在 `SECURITY.md`。

## 這個 repo 和使用者電腦的關係
- `wayne935/goal`（公開）是完整專案，**main 的根目錄同時是 GitHub Pages 網站**（https://wayne935.github.io/goal/）和 Android App 自動更新的來源。
- 使用者電腦上的工作資料夾是 `G:\我的雲端硬碟\Claude\Goal`（Google 雲端硬碟），裡面另外有私人資料：`data/`（紀錄）、`inbox/`（AI 判題結果、題單）、`env.txt`。**這些永遠不能進 repo**，`.gitignore` 已排除。
- 在雲端 session 改完後，使用者要把變更拉回 Goal 資料夾；結束時在回覆裡列出改了哪些檔案。

## 發佈規則（重要）
- **推到 main = 立刻上線**：Pages 一兩分鐘內更新，手機 App 下次開啟就會下載。開發請在分支上做，測過再合併到 main。
- 每次修改 `index.html`：`const APP_BUILD = N;` +1，`version.json` 的 `build` 改成同一個數字（App 比對兩者，不一致就不更新）。`android-app/www/` 是建置時才複製的，不用手動同步。
- 只改網頁時不用重新建置 App。改了 `android-app/` 的原生程式（Java、小工具 XML）時：`GoalUpdater.NATIVE_VERSION`（目前 3）+1，`version.json` 的 `minNative` 改成同一個數字，並告訴使用者要執行 `build-android.bat` 重新建置。
- 更新 `ROADMAP.md` 的狀態與「執行紀錄」（日期 + build + 一句話）。

## 架構（全部在單一 `index.html`）
- **Event sourcing**：`state.events` 是唯一的真相，XP、代幣、徽章進度、Boss 血量全部由 `compute()` 推算；刪除事件後數字會自動回推。新功能也要這樣做，不要另外存計算結果。
- 事件類型：`leetcode`、`habit`、`custom`、`redeem`、`restraint`（忍住）、`relapse`（犯了）、`contract_open`（承諾契約）、`freeze_buy`（保護卡）、`cheer`（加油：收到的有 `from`、送出的有 `to`）、`party_reward`（共同 Boss 獎勵）。新增類型要同時改：`compute()`、`validEvent()`、`normalize()`（給 inbox / AI 用）、`eventTitle()` / `eventIcon()`。`cheer`、`party_reward` 只由網頁自己產生，`normalize()` 故意不接受。
- 設定類資料（`habits`、`rewards`、`bosses`、`settings`、`problems`、`unlocked`）放在 `goal_meta` 同步：新增欄位要加進 `metaPayload()`、`syncNow()` 的拉取合併、`mergeState()`（備份匯入）、`migrate()`，並寫一個 `validXxx()` 檢查函式。舊版 App 推上來的設定可能缺欄位，缺的時候保留本機的。
- 一天從凌晨 `dayStartHour`（預設 4）點開始：日期一律用 `dayKey(ts)` / `todayKey()`，不要用原生 Date 的日期。
- 慶祝動畫：`celebrations` 佇列 + `showNextCelebration()`（徽章 `badge`、升級 `level`、打倒 Boss `boss`）。每台裝置「看過了沒」記在 localStorage（`goal.levelSeen`、`goal.bossSeen`…），第一次看到的東西只記錄不播放。
- 每日任務、每週目標、Boss 打倒獎勵都是在 `compute()` 裡加到當天的 bonus。
- 主要常數在檔案開頭：`DEFAULT_HABITS`、`DIFF`、`QUEST_POOL`、`WEEK_GOALS`、`BOSS`、`ACH`（徽章，`v: s => ...` 從 `compute()` 的結果取值，`legend: true` 是傳說級）。
- Android 橋接：`IS_NATIVE`、`nat(method, opts)` 呼叫 `GoalWidgetPlugin.java`；小工具資料由 `pushWidget()` 送出，小工具的打卡由 `drainWidgetQueue()` 收回。
- 雲端：Supabase（`SB` 常數，publishable key 本來就公開，靠 RLS 保護），資料表見 `sync/supabase-setup.sql`。
- **本機紀錄屬於某一個帳號**（`state.owner`，build 8）：`bindAccount()` 在開機與登入時檢查，換另一個帳號登入時把目前的 `state` 收進 localStorage `goal.v1@<帳號 id>`、換成新帳號自己的紀錄；`syncNow()` 只在 `state.owner === myUid()` 時同步，連結的 Goal 資料夾也只給 `data/goal-data.json` 裡記的那個帳號讀寫（`folderMine()`）。新增同步路徑時要守住這條規則，不然會把一個人的紀錄傳到另一個帳號。
- 隊友副本：資料表與政策在 `sync/party-setup.sql`（雲端 session 不能動資料庫，SQL 要使用者自己到 SQL Editor 執行）。網頁的所有隊伍 API 都包在 `partyApi`；隊伍資料（別人的資料）放在記憶體的 `party` 物件與 localStorage `goal.party`，**不進 `state`、也不進 `goal_meta`**。隊友只讀得到 `goal_party_days` 的每日摘要（`partyDayRows()` 在本機算好上傳），從雲端拉回來的一律經過 `validParty*()` / `validCheer()`，名字一律 `esc()`。共同 Boss 由 `partyBoss()` 在前端推算。

## 寫程式的慣例
- 照現有風格：緊湊的單行函式、`$()`、模板字串產生 HTML、`data-act` 事件委派（click / submit / change 都在檔案最後）。
- **所有顯示在畫面上的文字都要經過 `esc()`**；所有外部資料（inbox、貼上、備份、雲端）都要經過 `validXxx()`。這是 build 3 資安修正的重點，不要退步。
- 介面文字用繁體中文、口語、正面（例如「犯了不歸零」）；數字用 `fmt()`，數字字型加 `class="num"`。
- 顏色用 `:root` 的 CSS 變數；手機版斷點是 720px，底部分頁列目前 7 個分頁，再加就太擠了。
- 動畫都要尊重 `reducedMotion()`；音效用 `chime(kind)`，震動用 `haptic(kind)`。

## 怎麼測試
- `python serve.py` → http://localhost:8765/（只提供網頁檔案）。用瀏覽器開，不要登入雲端同步（那是使用者的真實資料）。
- Console 裡有 `window.__goal`（`state`、`compute()`、`ingest()`、`render()`、`checkUnlocks()`、`celebrate()`、`partyApi`、`party`、`syncParty()`、`partyBoss()`）。測隊伍時把 `__goal.partyApi` 的方法換成回傳假資料的函式，再呼叫 `__goal.syncParty()`。
- 改了 SQL 可以在本機測：雲端環境有 PostgreSQL 16，建一個假的 `auth` schema（`auth.users`、用 `request.jwt.claim.sub` 實作 `auth.uid()`）和 `anon` / `authenticated` 角色，再 `set role authenticated` 模擬不同使用者。
- 測多天的邏輯：改 localStorage 的 `goal.v1`（例如把 Boss 的 `createdAt` 或事件的 `ts` 往前調）後重新整理。測完 `localStorage.clear()`。
- 每個分頁都切一次，確認沒有出現「這一頁顯示不出來」，Console 沒有錯誤；舊資料（缺新欄位）要能正常載入。
