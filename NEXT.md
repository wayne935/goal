# 接下來要實作的事

> 更新：2026-10-25　｜　目前網頁 build 5，原生 NATIVE_VERSION 3（`version.json` 的 minNative 2）
> 先讀 `CLAUDE.md`（架構、發佈規則、測試方法）。設計理由與研究依據在 `PLAN.md` 第 8 節。

## 目前狀態
- 階段 1～3 完成：打卡、LeetCode 判題、徽章、商店、題單、每日任務、每週回顧、PWA、Supabase 同步、Android App 與小工具。
- **8.1 惡習 Boss 完成（build 5，2026-10-23）**：「惡習」分頁、`restraint` / `relapse` 事件、`simulateBosses()` 逐日推算血量、打倒動畫、存錢罐、6 個徽章。數值在 `BOSS` 常數。
  - 已知取捨：建立 Boss 的當天就算第一個攻擊日；手機底部分頁列已有 7 個分頁。
- 使用者還沒做的事：重新建置一次 App（讓原生震動與資安修正生效）、正常使用一週後回報數值手感。

## 1. 承諾契約（PLAN 8.2）← 下一個要做
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

**完成時**：`APP_BUILD` 與 `version.json` 改成 6，更新 `ROADMAP.md`、`PLAN.md` 8.2、`README.md`。只改網頁，不用重新建置 App。

## 2. 之後的候選（依建議順序）
1. **提醒通知**（ROADMAP 階段 2 第 5 項）：先做網頁開著時的本機提醒（例如晚上 10 點還沒運動、Boss 今天還沒忍住）。真正的手機推播要用 Android 原生（WorkManager + 通知），要 bump `NATIVE_VERSION` 並重新建置。
2. **小工具加「忍住了」按鈕**：原生改動（`GoalWidgetProvider.java`、`goal_widget.xml`、`GoalStore` 佇列支援 `restraint` 事件），要 bump `NATIVE_VERSION`、`minNative`，請使用者重新建置。
3. **隊友副本（PLAN 8.3）**：成本最高。需要新的 Supabase 資料表（`goal_parties`、`goal_party_members` 與只露出摘要的 view，RLS 只讓隊員互看），**SQL 要寫進 `sync/` 並請使用者自己到 Supabase SQL Editor 執行**（雲端 session 沒有資料庫權限）。公開註冊已關閉，朋友帳號要由使用者在 Supabase 後台邀請。
4. **自動判定半夜滑手機**：Android `UsageStatsManager`，需要使用者授權「使用情況存取權」，原生改動。
5. **LeetCode Chrome 擴充功能**（階段 4，可選）：偵測 Accepted，送出 `leetcode_submitted: true` 的事件。
6. **數值調整**：等使用者回報一週的使用手感，不要自己猜。
