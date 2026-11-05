# 接下來要實作的事

> 更新：2026-11-04　｜　目前網頁 build 16，原生 NATIVE_VERSION 4（`version.json` 的 minNative 4）
> 先讀 `CLAUDE.md`（架構、發佈規則、測試方法）。設計理由與研究依據在 `PLAN.md` 第 8 節。

## 目前狀態
- 階段 1～3 完成：打卡、LeetCode 判題、徽章、商店、題單、每日任務、每週回顧、PWA、Supabase 同步、Android App 與小工具。
- **8.1 惡習 Boss 完成（build 5，2026-10-23）**：「惡習」分頁、`restraint` / `relapse` 事件、`simulateBosses()` 逐日推算血量、打倒動畫、存錢罐、6 個徽章。數值在 `BOSS` 常數。
  - 已知取捨：建立 Boss 的當天就算第一個攻擊日；手機底部分頁列已有 7 個分頁。
- **8.2 承諾契約完成（build 6，2026-10-25）**：`contract_open` 事件與 `settleContracts()`、「今天」頁開局畫面（週一 / 每月 1 號 / 生日）、連續天數保護卡（`freeze_buy` + `planFreezes()`）、綁定獎勵（`state.bundles`）、4 個徽章。數值在 `CONTRACT`、`FREEZE` 常數，細節見 PLAN 8.2。
  - 使用者的決定：倍率依難度 1.5 / 2 倍（比最近 4 週平均難 = 2 倍）、簽約 1 小時內可取消、每週最多 2 份、保護卡 150 代幣最多 2 張、要做生日開局。
  - 已知取捨：連續空白的天數比手上的保護卡多時整段不補（卡退回、不浪費）；凌晨 `dayStartHour` 前簽的約算前一天那週；契約達成只跳 toast，沒有全螢幕動畫。
- 使用者還沒做的事：重新建置一次 App（讓原生震動與資安修正生效）、在設定填生日、正常使用一週後回報數值手感。
- **8.3 隊友副本完成（build 7，2026-10-25）**：`sync/party-setup.sql`（4 張表、3 個函式、RLS，已在本機 PostgreSQL 模擬 Supabase 實測，`sync/test/run-party-test.sh` 可重跑）、`partyApi` / `party` / `syncParty()` / `partyBoss()`、「今天」頁隊伍面板、設定頁隊伍管理與分享程度、改密碼、`cheer` / `party_reward` 事件、5 個徽章。數值在 `PARTY` 常數，細節見 PLAN 8.3。
  - 使用者的決定：朋友帳號由使用者在 Supabase 後台建、網頁可改密碼；預設只分享「有沒有紀錄 + 分類」；共同 Boss 每人 150 血、打倒每人 +40 XP / +60 代幣；收到加油 +5 代幣、每天最多 3 次計分。
  - 已知取捨：共同 Boss 的獎勵週日結束後才入帳（只檢查上一週，超過兩週沒開網頁會漏掉）；每台裝置用自己本機的紀錄算自己的傷害、用雲端摘要算隊友的，同步前後數字可能差一點；隊友可以竄改自己的摘要（只影響隊內公平）；這次沒做「隊友當契約見證人」和小工具顯示隊伍。
  - SQL 已執行、build 7 已上線。使用者還要做端對端測試（測試帳號 + 兩個瀏覽器，組隊、打卡、加油）。
- **build 8 修正（2026-10-25）**：同一個瀏覽器換帳號登入會把上一個帳號的本機紀錄同步過去。現在本機紀錄綁定帳號（`state.owner`、`bindAccount()`），細節見 `SECURITY.md`。被複製過資料的測試帳號要在 Supabase 刪掉重建。

- **build 9 鎖機專注與自動紀錄（2026-11-02，PLAN 第 9 節）**：原生 `GuardService` / `LockActivity` / `GuardState` / `GuardReceiver` / `HealthBridge`，網頁的 `focusPanel()`、`reviewPanel()`、`guardSettingsHTML()`、`syncHealth()`、`focus` / `gate` 事件。網頁在瀏覽器實測（含模擬 Android 橋接）；原生程式在雲端用 android-36.jar 編譯檢查過，**還沒在實機跑過**。
  - 使用者的決定：專注用白名單；緊急解鎖 = 沒收獎勵 + 扣代幣（每週加倍）+ 連勝歸零；社群 / 手遊參考 ClaryMind；學習類任務只能鎖機記錄，喝水這類用按的，分類由使用者在前端設定。
  - 已知取捨：守護服務是無障礙服務，App 被強制停止時鎖不住（所以專注 / 宵禁期間關掉服務 = 視同緊急解鎖）；Health Connect 只在打開 App 時讀；宵禁守住的獎勵在宵禁結束時（或下次打開 App）才入帳；分心 App 放行對全部分心 App 一起生效（跟 ClaryMind 一樣）。
  - 使用者還要做：重新建置 App、開權限、實際用一週回報（特別是鎖定畫面在 Pixel 9a / Android 17 上的行為）。
- **build 10 手機介面修正（2026-11-04）**：習慣設定卡片排版（980px 以下）、專注面板統計列間距、Health Connect 自動記錄的慶祝畫面（`celebrations` 新類型 `health`，`playHealth()`；`syncHealth()` 用 `compute()` 前後差算總獎勵、`S.per[id]` 算每筆）與 `healthSummary()`。
- **build 11 字體與選單美化（2026-11-04）**：`--ff-zh` 改成 Nunito → 昭源甜圓（英文字型排第一，中文自動落到甜圓）；`select.in` 在 `@supports (appearance:base-select)` 裡換樣式；開 / 關用 `sw(attrs, on)` 產生的開關（change 用 `t.checked`，`setGuardField()` 也認 checkbox）；Boss 難度用 `.seg.zh`。
- **build 12「今天」頁整理（2026-11-04）**：`viewToday()` 上方是 `.notes`（一行一條的提示：`reviewPanel()` 沒打開時、`contractNudge()`、連結資料夾），再來是「現在要處理」的東西（打開的回顧、進行中的專注、開局表單），下面是 `.tgrid` 兩欄；每個面板包在 `slot(順序, html)` 裡，960px 以下兩欄變成一欄、照 `--o` 排序。`contractInfo()` 給契約面板和提示共用。網頁版 `focusPanel()` 是可以展開的 `details.fmini`。手機頁首在 720px 以下是固定在上方的一行。
- **build 13（2026-11-05）**：手機底部 5 個分頁，`ME_TABS`（徽章牆、紀錄、設定）收進「我的」（`.tab-me`，720px 以下才出現；子分頁 `.subtabs` 在 `render()` 加在頁面最上面；上次看的記在 localStorage `goal.meTab`）。徽章牆每個分類是 `details.bcat`，展開的分類記在 `goal.badgeOpen`。遊戲設計候選：各領域等級（用現有 `catXp`）、每日寶箱（內容用 `hashStr(事件 id)` 決定）、外觀收藏、4～6 週賽季、斷掉後的回歸獎勵、LeetCode 間隔複習、每月回顧卡。
- **build 14（2026-11-05）**：各領域等級：`compute()` 多一個 `S.skills`（`skillKey()`：興趣類每個習慣一條 `h:<id>`，其他照分類；只算事件本身的 XP，加成不算），`skillLevel()` 曲線是 40 + 20×L，稱號在 `SKILL_TITLES`（每 3 級換一個），`skillsPanel()` 放在徽章牆最上面，升級提示在 `checkMilestones()`（這台裝置第一次看到的只記在 `goal.skillSeen`）。間隔複習：`reviewQueue()` 從 leetcode 事件推算（`REVIEW_GAPS` = 3/7/21/60 天，通過滿 5 天就不再提醒），`reviewQueuePanel()` 在題單頁最上面，「今天」頁記錄解題的說明會顯示到期題數。
- **build 15（2026-11-05）**：`CHEST`、`COMEBACK`、`COSMETICS` 常數。寶箱 = `chest_open` 事件（`day` 是任務完成那天），`compute()` 在每日任務之後用 `chestRoll(day, owned)` 推算內容（`hashStr`），結果在 `S.chests`；外觀 = `cosmetic_buy` 事件，擁有的在 `S.owned`，套用哪一個是 `settings.theme`（`validSettings()` 只收目錄裡的 id），`applyTheme()` 換 `--felt` 系列變數。回歸加成 `comebackInfo()`：每天事件本身的 XP 記在 `d.evXp`，加成記在 `d.comebackXp`；「今天」頁有提示。
- **build 16（2026-11-05）**：賽季 `SEASON`（`anchor` 2026-11-02、28 天、30 階 × 80 XP、`rewards` 只給代幣），`compute()` 在回歸加成之後照日期累積 `d.xp` 算 `S.seasons[n]`（`tier`、`doneDay`），`seasonPanel()` 在「今天」頁（slot 4）。每月回顧 `monthRecap()` / `recapPanel()`（紀錄頁最上面）/ `recapImage()`（canvas 畫圖，先用 `document.fonts.load()` 載入要用的字）。新徽章 `ss_1`、`ss_3`、`ss_13`、`ch_10`、`ch_legend`。
  - 還沒做：季節 Boss（PLAN 10.3）；回顧卡在 App 裡只能截圖（WebView 不能長按存圖）；晚上提醒改成「再做 1 件就保住 N 天」要動原生通知文字。
  - 數值都是草案（賽季每階 80 XP、各階代幣、寶箱機率），等使用者用一兩週回報手感再調。

## 1. 之後的候選（依建議順序）
1. **實機回饋修正**：build 9 的鎖定畫面、通知、Health Connect 第一次在 Pixel 9a 上跑，先處理使用者回報的問題。
2. **小工具加「忍住了」按鈕、顯示專注倒數**：原生改動（`GoalWidgetProvider.java`、`goal_widget.xml`、`GoalStore` 佇列支援 `restraint` 事件），要 bump `NATIVE_VERSION`、`minNative`，請使用者重新建置。
3. **惡習 Boss 接上分心 App 放行紀錄**（`gate` 事件）：放行太多自動算犯了；宵禁取代原本「自動判定半夜滑手機」的構想。
3b. **筆電也一起鎖**：Chrome 擴充功能在專注期間擋分心網站。
4. **LeetCode Chrome 擴充功能**（階段 4，可選）：偵測 Accepted，送出 `leetcode_submitted: true` 的事件。
5. **數值調整**：等使用者回報一週的使用手感，不要自己猜（契約倍率、保護卡價格也一樣）。
