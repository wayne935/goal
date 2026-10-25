# 接下來要實作的事

> 更新：2026-10-25　｜　目前網頁 build 6，原生 NATIVE_VERSION 3（`version.json` 的 minNative 2）
> 先讀 `CLAUDE.md`（架構、發佈規則、測試方法）。設計理由與研究依據在 `PLAN.md` 第 8 節。

## 目前狀態
- 階段 1～3 完成：打卡、LeetCode 判題、徽章、商店、題單、每日任務、每週回顧、PWA、Supabase 同步、Android App 與小工具。
- **8.1 惡習 Boss 完成（build 5，2026-10-23）**：「惡習」分頁、`restraint` / `relapse` 事件、`simulateBosses()` 逐日推算血量、打倒動畫、存錢罐、6 個徽章。數值在 `BOSS` 常數。
  - 已知取捨：建立 Boss 的當天就算第一個攻擊日；手機底部分頁列已有 7 個分頁。
- **8.2 承諾契約完成（build 6，2026-10-25）**：`contract_open` 事件與 `settleContracts()`、「今天」頁開局畫面（週一 / 每月 1 號 / 生日）、連續天數保護卡（`freeze_buy` + `planFreezes()`）、綁定獎勵（`state.bundles`）、4 個徽章。數值在 `CONTRACT`、`FREEZE` 常數，細節見 PLAN 8.2。
  - 使用者的決定：倍率依難度 1.5 / 2 倍（比最近 4 週平均難 = 2 倍）、簽約 1 小時內可取消、每週最多 2 份、保護卡 150 代幣最多 2 張、要做生日開局。
  - 已知取捨：連續空白的天數比手上的保護卡多時整段不補（卡退回、不浪費）；凌晨 `dayStartHour` 前簽的約算前一天那週；契約達成只跳 toast，沒有全螢幕動畫。
- 使用者還沒做的事：重新建置一次 App（讓原生震動與資安修正生效）、在設定填生日、正常使用一週後回報數值手感。

## 1. 下一個要做的（依建議順序）
1. **提醒通知**（ROADMAP 階段 2 第 5 項）：先做網頁開著時的本機提醒（例如晚上 10 點還沒運動、Boss 今天還沒忍住、契約週日還差幾次）。真正的手機推播要用 Android 原生（WorkManager + 通知），要 bump `NATIVE_VERSION` 並重新建置。
2. **小工具加「忍住了」按鈕**：原生改動（`GoalWidgetProvider.java`、`goal_widget.xml`、`GoalStore` 佇列支援 `restraint` 事件），要 bump `NATIVE_VERSION`、`minNative`，請使用者重新建置。
3. **隊友副本（PLAN 8.3）**：成本最高。需要新的 Supabase 資料表（`goal_parties`、`goal_party_members` 與只露出摘要的 view，RLS 只讓隊員互看），**SQL 要寫進 `sync/` 並請使用者自己到 Supabase SQL Editor 執行**（雲端 session 沒有資料庫權限）。公開註冊已關閉，朋友帳號要由使用者在 Supabase 後台邀請。可以和契約結合：指定一位隊友當見證人。
4. **自動判定半夜滑手機**：Android `UsageStatsManager`，需要使用者授權「使用情況存取權」，原生改動。
5. **LeetCode Chrome 擴充功能**（階段 4，可選）：偵測 Accepted，送出 `leetcode_submitted: true` 的事件。
6. **數值調整**：等使用者回報一週的使用手感，不要自己猜（契約倍率、保護卡價格也一樣）。
