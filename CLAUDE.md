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
- 只改網頁時不用重新建置 App。改了 `android-app/` 的原生程式（Java、小工具 XML）時：`GoalUpdater.NATIVE_VERSION`（目前 6）+1，`version.json` 的 `minNative` 改成同一個數字，並告訴使用者要執行 `build-android.bat` 重新建置。
- 更新 `ROADMAP.md` 的狀態與「執行紀錄」（日期 + build + 一句話）。

## 架構（全部在單一 `index.html`）
- **Event sourcing**：`state.events` 是唯一的真相，XP、代幣、徽章進度、Boss 血量全部由 `compute()` 推算；刪除事件後數字會自動回推。新功能也要這樣做，不要另外存計算結果。
- 事件類型：`leetcode`、`habit`、`custom`、`redeem`、`restraint`（忍住）、`relapse`（犯了）、`contract_open`（承諾契約）、`freeze_buy`（保護卡）、`cheer`（加油：收到的有 `from`、送出的有 `to`）、`party_reward`（共同 Boss 獎勵）、`party_quest`（每週隊伍任務）、`gift_send` / `gift`（送禮 / 收到禮物）、`witness_verdict`（契約見證結果）、`group_focus`（一起專注的團隊獎勵）、`focus`（鎖機專注 / 宵禁，`kind` + `result`）、`gate`（分心 App 放行）、`chest_open`（寶箱，`day`）、`cosmetic_buy`（外觀）、`journal`（日記，`day`、`words`、`mood`、`tags`，內容在加密的 `enc`）、`couple`（情侶隊的獎勵，`act` = share / reply / answer、`day`，id 固定是 `cp-<act>-<day>`）、`watch`（影劇心得：`day`、`media` = movie / tv、`title`、`tmdb`、`season`、`episode`、`rating` 1～10 半星制、`review` 明文、`words`、`with` = 一起看的）。新增類型要同時改：`compute()`、`validEvent()`、`normalize()`（給 inbox / AI 用）、`eventTitle()` / `eventIcon()`。`cheer`、`party_reward`、`party_quest`、`gift_send`、`gift`、`witness_verdict`、`group_focus`、`chest_open`、`cosmetic_buy`、`journal`、`couple`、`watch` 只由網頁自己產生，`focus`、`gate` 只由原生端產生（`drainWidgetQueue()` 直接用 `validEvent()` 收），`normalize()` 故意都不接受。
- 設定類資料（`habits`、`rewards`、`bosses`、`settings`、`problems`、`unlocked`）放在 `goal_meta` 同步：新增欄位要加進 `metaPayload()`、`syncNow()` 的拉取合併、`mergeState()`（備份匯入）、`migrate()`，並寫一個 `validXxx()` 檢查函式。舊版 App 推上來的設定可能缺欄位，缺的時候保留本機的。
- 一天從凌晨 `dayStartHour`（預設 4）點開始：日期一律用 `dayKey(ts)` / `todayKey()`，不要用原生 Date 的日期。
- 慶祝動畫：`celebrations` 佇列 + `showNextCelebration()`（徽章 `badge`、升級 `level`、打倒 Boss `boss`、季節 Boss `sboss`、寶箱 `chest`、Health Connect `health`、共同 Boss `party`）。每台裝置「看過了沒」記在 localStorage（`goal.levelSeen`、`goal.bossSeen`…），第一次看到的東西只記錄不播放。
- 每日任務、每週目標、Boss 打倒獎勵都是在 `compute()` 裡加到當天的 bonus。
- 主要常數在檔案開頭：`DEFAULT_HABITS`、`DIFF`、`QUEST_POOL`、`WEEK_GOALS`、`BOSS`、`ACH`（徽章，`v: s => ...` 從 `compute()` 的結果取值，`legend: true` 是傳說級）。
- Android 橋接：`IS_NATIVE`、`nat(method, opts)` 呼叫 `GoalWidgetPlugin.java`；小工具資料由 `pushWidget()` 送出，小工具的打卡由 `drainWidgetQueue()` 收回。
- **鎖手機（build 9）**：原生端 `GuardState.java` 是規則的來源（專注、宵禁、分心 App 放行、守護服務開著的時段），事件走 GoalStore 佇列並直接上傳雲端；網頁用 `pushGuard()` 送設定（`state.settings.guard`，`validGuard()`）、`refreshGuard()` 讀狀態（`guard` 物件，不進 `state`）。習慣的 `log`（`manual` / `focus` / `both`）決定能不能用按的：`logMode(h) === 'focus'` 的習慣在 `logHabit()`、`normalize()`、小工具都會被擋，只有 `focus` 事件能記。懲罰算法 `focusPenalty()`，緊急解鎖的事件 `locked()` 不能刪。
- **日記（build 22）**：標題和內文用日記密碼加密（`jSeal()` / `jDecrypt()`），網頁其他地方只能用明文的 `day` / `words` / `mood` / `tags`，不要把內文放進事件的其他欄位或 `partyDayRows()`。金鑰 `state.jkey` 在 `goal_meta` 同步、合併用 `pickJkey()`（不看 `metaAt`）；這台裝置的金鑰在 IndexedDB。寫日記畫面 `.jwv` 放在 `#view` 外面，`render()` 不會打斷輸入。每日任務加新任務要給 `since`（`questsFor()` 用雜湊挑任務，池子變大會改到以前的日子、讓寶箱失效）。
- 雲端：Supabase（`SB` 常數，publishable key 本來就公開，靠 RLS 保護），資料表見 `sync/supabase-setup.sql`。
- **本機紀錄屬於某一個帳號**（`state.owner`，build 8）：`bindAccount()` 在開機與登入時檢查，換另一個帳號登入時把目前的 `state` 收進 localStorage `goal.v1@<帳號 id>`、換成新帳號自己的紀錄；`syncNow()` 只在 `state.owner === myUid()` 時同步，連結的 Goal 資料夾也只給 `data/goal-data.json` 裡記的那個帳號讀寫（`folderMine()`）。新增同步路徑時要守住這條規則，不然會把一個人的紀錄傳到另一個帳號。
- 隊友副本：資料表與政策在 `sync/party-setup.sql`（build 20 的聊天、送禮、戳一下、見證人在 `sync/party-v2.sql`，沒執行時 `party.v2 = false`、功能自動隱藏；build 21 的一起專注在 `sync/party-v3.sql`，`party.v3`；build 23 的情侶隊在 `sync/party-v4.sql`，`party.v4`；build 24 的一起看的片和片單在 `sync/party-v5.sql`，`party.v5`）（雲端 session 不能動資料庫，SQL 要使用者自己到 SQL Editor 執行）。網頁的所有隊伍 API 都包在 `partyApi`；隊伍資料（別人的資料）放在記憶體的 `party` 物件與 localStorage `goal.party`，**不進 `state`、也不進 `goal_meta`**。隊友只讀得到 `goal_party_days` 的每日摘要（`partyDayRows()` 在本機算好上傳），從雲端拉回來的一律經過 `validParty*()` / `validCheer()`，名字一律 `esc()`。共同 Boss 由 `partyBoss()` 在前端推算。
- **情侶隊（build 23）**：`goal_parties.kind`（`friends` / `couple`）和 `since`（紀念日），`isCouple()`、`partner()`、`partyCap()`。交換日記 = 按「分享」時把那一篇日記的**明文**複製到 `goal_exchange`（`cpShare()` / `cpUnshare()`，upsert 用 `on_conflict=author,entry_id`），自己的日記事件本身不改、仍然加密；分享狀態只看雲端（`party.shared` 來自 `partyApi.myShared()`）。對方的日記、回覆、每日一問的答案只放在記憶體（`saveParty()` 不存），一律經過 `validExchange()` / `validReply()` / `validAnswer()` 和 `esc()`。每日一問的題目 `QUESTIONS[qOf(day)]`（答案記 `qid`，加題目不會改到以前的答案），兩人都答了才看得到對方的（RLS 擋）。心情只在情侶隊、`settings.coupleMood !== false` 時放進 `goal_party_days.mood`。玫瑰外觀 `couple: true`，用 `ownsTheme()` 判斷。
- **影劇心得（build 24）**：TMDB 的金鑰不在網頁裡，放在使用者 Supabase 的 Edge Function `tmdb`（原始碼 `sync/tmdb-function.ts`，secret `TMDB_KEY`，關掉 Verify JWT、函式自己用 `/auth/v1/user` 檢查登入，只放行搜尋和電影 / 影集資料的路徑）；網頁用 `tmdbApi` 呼叫，回來的資料經過 `validTmdbItem()` / `validTmdbSeasons()`，海報只收 `posterOk()` 的路徑、組成 `image.tmdb.org` 網址（CSP `img-src` 有開）。獎勵在 `compute()`：心得滿 `WATCH.min` 字、每部電影 / 每一季（`watchKey()`）一次、一天最多 `WATCH.daily` 部，不算「有紀錄」。寫心得畫面 `.wwv` 跟日記一樣放在 `#view` 外面（`ww` 狀態、`wwOpen()` / `wwForm()` / `wwSave()`）。情侶隊的「一起看的」= 存檔時把那一筆複製到 `goal_watch_together`（`cwShare()` / `cwUnshare()`，upsert `on_conflict=user_id,media,tmdb,season`），兩個人都寫了同一部（同一季）才讀得到對方的（RLS + `goal_watched()`），還沒寫的人只能從 `goal_watch_titles()` 知道對方看了哪部；對方的心得 `party.wRows` 只放記憶體（`saveParty()` 不存），片單 `party.wList`，都經過 `validWatchRow()` / `validWatchTitle()` / `validWatchItem()`。頁尾要保留 TMDB 標示（`tmdbCredit()`，TMDB 使用條款要求）。

## 寫程式的慣例
- 照現有風格：緊湊的單行函式、`$()`、模板字串產生 HTML、`data-act` 事件委派（click / submit / change 都在檔案最後）。
- **所有顯示在畫面上的文字都要經過 `esc()`**；所有外部資料（inbox、貼上、備份、雲端）都要經過 `validXxx()`。這是 build 3 資安修正的重點，不要退步。
- 介面文字用繁體中文、口語、正面（例如「犯了不歸零」）；數字用 `fmt()`，數字字型加 `class="num"`。
- 選項的寫法：只有開 / 關用 `sw(attrs, on)` 開關（讀 `t.checked`）；2～4 個短選項用 `.seg` 分段按鈕（中文字加 `zh`）；長清單才用 `<select class="in">`（已用 `base-select` 美化，不支援的瀏覽器退回原生）。
- 字體：`--ff-zh` 是 Nunito（英文）→ 昭源甜圓（中文），數字 `--ff-num` 是 Barlow Condensed。
- 「今天」頁（`viewToday()`）：新面板要包進 `slot(順序, html)`，手機一欄時照順序排；一次性的提示寫成一行的 `.note` 放進 `.notes`，不要再做整塊的 callout 把打卡格往下擠。
- 顏色用 `:root` 的 CSS 變數；手機版斷點是 720px，手機底部分頁列是 5 個（日記、影劇、徽章牆、紀錄、設定收在「我的」，`ME_TABS`，400px 以下子分頁只留文字），桌面是 9 個；新增分頁前先想能不能收進「我的」。
- 動畫都要尊重 `reducedMotion()`；音效用 `chime(kind)`，震動用 `haptic(kind)`。

## 怎麼測試
- `python serve.py` → http://localhost:8765/（只提供網頁檔案）。用瀏覽器開，不要登入雲端同步（那是使用者的真實資料）。
- Console 裡有 `window.__goal`（`state`、`compute()`、`ingest()`、`render()`、`checkUnlocks()`、`celebrate()`、`partyApi`、`party`、`syncParty()`、`partyBoss()`、`guard`、`addEvent()`、`validEvent()`、`focusPenalty()`、`G()`）。頂層的函式（`jSeal()`、`validJkey()`、`mergeState()`…）也可以直接在 Console 呼叫。
- 測日記：在日記分頁設定一組測試密碼，寫完看 `__goal.state.events` 裡沒有明文；「日記密碼」裡的「在這台裝置上鎖起來」可以測輸入密碼和備用碼。
- 測鎖機功能：在 `addInitScript` 裡放一個假的 `window.Capacitor`（`isNativePlatform: () => true`、`nativePromise` 依方法名稱回傳假資料：`guardStatus`、`focusStart`、`drainQueue`、`healthRead`…），就能在瀏覽器裡測專注面板、緊急解鎖、Health Connect 轉換。
- 原生程式在雲端可以做編譯檢查：從 GitHub raw 下載 `Reginer/aosp-android-jar` 的 `android-36/android.jar`，Capacitor 類別寫幾個空殼，`javac -cp android.jar` 編譯 `com/wayne/goal/*.java`（Google Maven 連不到，不能跑 Gradle）。空殼要跟真的 Capacitor 一樣：`JSObject.fromJSONObject()` 會丟 checked `JSONException`，呼叫的地方都要包 try-catch（build 9 的 `guardStatus()` 漏了，空殼又沒丟例外，所以一直沒抓到）。
- 測隊伍時把 `__goal.partyApi` 的方法換成回傳假資料的函式，再呼叫 `__goal.syncParty()`。
- 測影劇：雲端 session 連不到 TMDB（api.themoviedb.org、image.tmdb.org 都被擋），用 Playwright 的 `route` 攔 `…supabase.co/functions/v1/tmdb?path=…` 回假的搜尋結果（記得放幾筆壞資料：person、`javascript:` 海報、`<img onerror>` 片名），`image.tmdb.org` 回一張假圖。代轉函式本身用 `node sync/test/tmdb-function-test.mjs` 測。
- 改了 SQL 可以在本機測：雲端環境有 PostgreSQL 16，建一個假的 `auth` schema（`auth.users`、用 `request.jwt.claim.sub` 實作 `auth.uid()`）和 `anon` / `authenticated` 角色，再 `set role authenticated` 模擬不同使用者。
- 測多天的邏輯：改 localStorage 的 `goal.v1`（例如把 Boss 的 `createdAt` 或事件的 `ts` 往前調）後重新整理。測完 `localStorage.clear()`。
- 每個分頁都切一次，確認沒有出現「這一頁顯示不出來」，Console 沒有錯誤；舊資料（缺新欄位）要能正常載入。
