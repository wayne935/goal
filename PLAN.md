# Goal：自我鼓勵成就系統 · 規劃

> 最後更新：2026-10-22（新增第 8 節：階段 5 新功能設計）
> 一句話目標：讓「解完一題」「做了一件對自己好的事」都能被看見、被計分，並換到真正的獎勵。

---

## 1. 核心想法

| 元素 | 作用 |
|---|---|
| **紀錄（事件）** | 每件做過的事都是一筆事件：解題、運動、讀論文、喝水… |
| **XP / 等級** | 長期累積感，只升不降 |
| **代幣** | 可以花掉的貨幣，到「商店」換自己訂的獎勵（看一集劇、買飲料） |
| **徽章（成就）** | 里程碑，解鎖時有全螢幕動畫，未解鎖時顯示進度環 |
| **連續天數** | 每天至少紀錄一件事就延續 |

設計原則：
- **所有數字都由事件算出來**（event sourcing）。XP、代幣、徽章進度都不是另外存的數字，刪掉一筆紀錄，所有數字會自動回推，不會壞掉。
- **一天從凌晨 4 點開始算**（可在設定改）。凌晨 2 點解的題算前一天，不會因為熬夜而斷掉連續天數。
- **不獎勵傷身的行為**：沒有「深夜解題」徽章，反而有「早上 9 點前解題」徽章。

---

## 2. 三階段路線

### 第一階段：網頁版（本次完成）
- 單一 `index.html`，用 Chrome / Edge 直接打開即可，不需要安裝任何東西。
- 功能：習慣打卡、手動新增解題、等級 / 代幣 / 連續天數、徽章牆與解鎖動畫、獎勵商店、歷史紀錄與熱力圖。
- **連結資料夾**：按一次「連結 Goal 資料夾」後，網頁會：
  - 每隔幾秒讀取 `inbox/` 裡 AI 寫入的判題結果，自動入帳並跳出成就；
  - 把完整紀錄存到 `data/goal-data.json`（因為在 Google 雲端硬碟裡，會自動備份與同步）。
- 沒連結資料夾時，資料存在瀏覽器（localStorage），也可以手動「貼上 AI 判定 JSON」。

### 第二階段：PWA（之後）
- 加 manifest + service worker，讓手機可以「加入主畫面」、離線使用。
- 資料同步：手機無法讀 G 槽，需要一個雲端儲存。候選方案：
  1. Google Drive API（資料已經在雲端硬碟，最自然）；
  2. Supabase / Firebase（免費額度足夠，雙向同步較簡單）。
- 推播：「今天還沒運動」之類的提醒（iOS 需加入主畫面後才有推播）。

### 第三階段：手機 widget（之後）
- 純網頁做不到 widget，需要原生外殼。兩條路：
  - **Capacitor**：保留網頁程式碼，用 `capacitor-widget-bridge` 做 iOS（SwiftUI）/ Android widget。
  - **Expo（React Native）**：已安裝 expo 外掛，可直接協助。
- Widget 內容：今日進度環、連續天數、2–3 個一鍵打卡按鈕（喝水 +1、運動完成）。
- 注意：iOS widget 需要 Mac + Xcode 編譯；Android 用 Windows 即可。

---

## 3. 解題判定：交給本地 AI（不需串 API）

### 評估
你的做法（本地寫程式 → 請 AI 檢查 → 正確就記到 JSON）**可行，而且不需要 API**：AI 本身就是你在用的 Claude Code，網頁只需要一個固定的檔案格式來接收結果。

但有一點要注意：**只讓 AI「讀」程式碼判斷對錯，不夠可靠**（這正是 LLM 幻覺問題）。所以判題指令要求 AI 必須「跑」測試，並把證據寫進 JSON：
1. 題目給的範例；
2. AI 自己設計的邊界測資（空陣列、單一元素、重複值、極值…）；
3. **對拍**：AI 另外寫一個暴力解，用隨機測資比對兩者輸出（differential testing）；
4. 依題目限制估算複雜度，明顯會超時（例如 n=10⁵ 卻是 O(n²)）也判為不通過。

最終的真相還是 LeetCode 提交；JSON 裡有 `leetcode_submitted` 欄位可以記錄。

### 流程
```
你寫完 solution.py
   │  在 Claude Code 輸入：/judge path/to/solution.py
   ▼
Claude Code：讀題 → 寫測資 → 跑測試 + 對拍 → 判定
   │  寫入一個檔案：Goal/inbox/20261020-031500-two-sum.json
   ▼
網頁（已連結資料夾）：幾秒內讀到 → 入帳 XP/代幣 → 跳出成就
```
- **一題一個檔案**寫進 `inbox/`，網頁只讀不改，避免兩邊同時寫同一個檔案造成衝突。
- 每筆有唯一 `id`，重複讀取不會重複入帳。
- 判定失敗（rejected）也會寫入：不給獎勵，但之後同一題通過可以拿「屢敗屢戰」徽章。
- 用網頁版 Claude 聊天時：用 `ai-judge/chat-prompt.md`，AI 會輸出 JSON，貼到網頁的「貼上 AI 判定」即可。

### 事件格式（`goal.event/v1`）
```json
{
  "schema": "goal.event/v1",
  "id": "lc-20261020-031500-two-sum",
  "type": "leetcode",
  "ts": "2026-10-20T03:15:00+08:00",
  "problem": { "id": 1, "slug": "two-sum", "title": "Two Sum", "difficulty": "easy" },
  "verdict": "accepted",
  "evidence": {
    "tests_passed": 18, "tests_total": 18,
    "edge_cases": ["最小長度 n=2", "負數", "答案在尾端"],
    "brute_force_crosscheck": true, "random_trials": 300
  },
  "complexity": { "time": "O(n)", "space": "O(n)" },
  "language": "python",
  "reviewer": "claude-code",
  "leetcode_submitted": false,
  "notes": "一句話評語",
  "file": "leetcode/0001_two_sum.py"
}
```
其他類型（以後 AI 也能幫忙記錄）：
```json
{ "schema": "goal.event/v1", "id": "h-20261020-run", "type": "habit", "habit": "exercise",
  "ts": "2026-10-20T18:30:00+08:00", "note": "跑步 5km" }
```

---

## 4. 經濟系統（初版數值，之後依手感調整）

| 行為 | XP | 代幣 | 每日上限 |
|---|---|---|---|
| LeetCode Easy / Medium / Hard | 10 / 25 / 50 | 6 / 15 / 30 | 無 |
| 重解舊題（複習） | 40% | 40% | 無 |
| 運動 | 15 | 10 | 2 |
| 喝水（一杯） | 2 | 1 | 8 |
| 睡滿 7 小時 | 15 | 10 | 1 |
| 散步 / 伸展 | 8 | 5 | 2 |
| 讀論文 | 20 | 12 | 3 |
| 寫論文 / 跑實驗（一個番茄鐘） | 20 | 12 | 4 |
| 整理環境 | 8 | 5 | 1 |
| 解鎖徽章 | — | 每個 20–300 | — |

- 等級：從 L 升到 L+1 需要 `80 + 40×L` XP。
- 習慣、獎勵都能在網頁「設定 / 商店」自行新增與修改。

---

## 5. 徽章清單（54 個）

**v1 基本款（32 個）**
- **程式**：第一題、10/50/100/300 題、第一題 Hard、10 題 Hard、25 題 Medium、同一天解出三種難度、一天 5 題、連續 7/30 天解題、屢敗屢戰、溫故知新（重解 5 題）、早上 9 點前解題
- **健康**：第一次運動、運動 30 次、連續 7 天運動、連續 7 天喝滿 8 杯、睡飽 14 天
- **研究**：讀 10/50 篇論文、寫論文 20/100 個時段
- **生活**：連續紀錄 3/7/30 天、等級 5/10、同一天程式+健康+研究都有紀錄、第一次兌換獎勵、累計賺 1000 代幣

**v2 為 Wayne 設計（22 個）**
| 徽章 | 條件 | 設計理由 |
|---|---|---|
| 零幻覺 | 10 題通過時附有對拍證據 | 呼應幻覺研究：驗證過才算數 |
| Ground Truth | 5 題在 LeetCode 官方也通過 | AI 判定之外的真實標籤 |
| 自我蒸餾 | 重解自己做過的題 15 次 | 從自己的輸出再學一次（OPSD） |
| Weak-to-Strong | 解出 10 題 Easy 與 5 題 Hard | 研究興趣之一 |
| 動態規劃 / 圖與樹 / 全能選手 | DP 10 題、圖或樹 10 題、8 種主題 | 面試準備的弱點覆蓋 |
| Builder | 推進個人專案 20 次 | Goal、4C agent 等 side project |
| 夜貓轉型 / 作息回正 | 1 點前上床累計 7 天 / 連續 5 天 | 常在凌晨工作，溫和拉回作息 |
| 跑起來了 / Ablation 狂人 | 實驗 10 / 50 次 | 論文實驗進度 |
| 引用鏈追蹤者 | 追 10 條引用鏈 | 文獻回顧用的 citation chaining |
| Bass Line / 每日律動 / Groove 職人 | 練貝斯 10 次 / 連續 7 天 / 50 次 | 興趣：貝斯 |
| 開局 / 中局思考 | 西洋棋練習 10 / 50 次 | 興趣：正在學西洋棋 |
| 四重奏 | 同一天解題、運動、讀論文、練貝斯 | 爵士 / 放克樂手的完美一天 |
| 大三元 | 同一天三個分類各拿 20 XP 以上 | NBA triple-double |
| 開放世界 | 7 天內五個分類都有紀錄 | 開放世界遊戲：每一塊地圖都去過 |
| 等級 20 | 升到等級 20 | 長期目標 |

v2 同時新增：習慣（跑實驗、追引用鏈、推進個人專案、練貝斯、西洋棋、1 點前上床）、「興趣」分類、LeetCode 主題標籤（AI 判題會填 `problem.tags`），以及商店獎勵（看 NBA、看電影、開放世界遊戲兩小時、新貝斯弦）。舊資料會自動升級，不會遺失。

---

## 6. 資料夾結構

```
Goal/
├── index.html              ← 主程式，用 Chrome / Edge 打開
├── PLAN.md                 ← 本文件
├── README.md               ← 使用說明
├── inbox/                  ← AI 判題結果放這裡（_ 開頭的檔案會被忽略）
│   └── _example.json
├── data/
│   └── goal-data.json      ← 網頁自動存的完整紀錄（連結資料夾後產生）
└── ai-judge/
    ├── judge.md            ← Claude Code 的 /judge 指令
    └── chat-prompt.md      ← 網頁聊天版的判題提示詞
```

---

## 7. 待決定 / 之後再做

- [ ] 數值手感：用一週後再調整 XP 與代幣。
- [ ] LeetCode 自動偵測：Chrome 擴充功能偵測「Accepted」，或讀公開提交紀錄（可與 AI 判題並存）。
- [ ] 第二階段的同步方案：Google Drive API 或 Supabase。
- [ ] 第三階段：Capacitor 或 Expo。
- [ ] 是否把 `/judge` 做成 Claude 帳號的 skill，讓 Cowork 與 Claude Code 都能用。

---

## 8. 階段 5 新功能設計（2026-10-22 構想，尚未實作）

目前 Goal 很完整地處理「做好事 → 拿獎勵」，但還缺三塊：**克制**（不做某件事）、**有風險的承諾**、**別人的存在**。以下三個功能依實作順序排列。

### 8.1 惡習 Boss：給「克制」一個對手
**依據**
- YouGov：美國人最常有的壞習慣包括存錢不夠、拖延、睡太少、熬夜；滑手機、睡前螢幕這類科技習慣是最少人成功戒掉的。
- Talker Research（2024，2,000 人）：42% 的人用滑社群逃避待辦清單。
- 一次失手就全部歸零，很容易讓人乾脆放棄（habit tracker 使用者常見的「斷一次就崩潰」）。

**玩法**
- 自訂要克制的事（半夜滑手機、手搖飲、衝動購物、打開社群），每一個都是一隻有血量的 Boss。
- 每多一天沒犯 = 一次攻擊；按「忍住了」= 暴擊，另給代幣。
- **犯了不歸零**：Boss 回一部分血（初版 20%）。
- **存錢罐**：忍住一筆消費時記下金額，顯示累計省下的新台幣（真錢，不是代幣）。
- 打倒 Boss → 傳說級徽章；之後可以 Lv2（更難）再出現。

**實作**（✅ 2026-10-23，build 5）
- 新事件類型：`restraint`（忍住，可帶 `amount` 金額）、`relapse`（犯了）；Boss 血量由事件推算，沿用 event sourcing。
- Boss 本身（名稱、圖示、難度、是否花錢、建立時間）存在 `state.bosses`，跟習慣一樣走 `goal_meta` 同步。
- 初版數值（`index.html` 的 `BOSS` 常數）：
  | 項目 | 數值 |
  |---|---|
  | 血量 | 難度天數（7 / 14 / 30）× 10，Lv2 起每級多 50% |
  | 撐過一天沒犯 | 10 點傷害（過了一天的起點時間才結算） |
  | 「忍住了」暴擊 | 8 點傷害、+5 XP、+8 代幣，每隻 Boss 每天最多 2 次（超過只記錄，金額仍算進存錢罐） |
  | 犯了 | Boss 回 20% 血（不超過上限），連續沒犯天數歸零 |
  | 打倒 | +50 XP × 等級、+100 代幣 × 等級，下一級立刻出現 |
- 新徽章：第一刀、跌倒再站起（犯了後 2 天內又忍住）、戒斷一個月、真的省到錢（NT$1,000）、屠龍者與惡習剋星（傳說級）。
- AI 也能記錄：`{ "type": "restraint", "boss": "手搖飲", "amount": 60 }`，`boss` 可以寫名稱或 id。
- 之後：Android App 讀取系統螢幕使用時間（UsageStatsManager），自動判定「今晚有沒有滑到半夜」；桌面小工具加「忍住了」按鈕。

### 8.2 承諾契約：拿代幣下注，每週一重新開局
**依據**
- 新開始效應（Dai, Milkman & Riis 2014）：週一、月初、生日之後，人最容易開始新目標（diet 搜尋、健身房次數在這些時間點衝高）。
- stickK：有押錢的人 78% 達成目標，沒押的 35%（使用者自己選擇要不要押，不能直接當成因果）。
- Duolingo：保護卡持有上限從 1 張提高到 2 張，每日活躍使用者 +0.38%，且沒有因此更常偷懶。
- 誘惑綁定（Milkman 等 2013）：喜歡的東西只在做討厭的事時享受，健身房次數 +51%。

**玩法**
- 每週一（還有月初、生日）跳出「開局」畫面：選一個本週目標，押代幣（例如 100）。達成拿回 1.5～2 倍，失敗沒收。
- 每份契約附 1 天緩衝日。
- 商店新增「連續天數保護卡」，最多持有 2 張。
- 「綁定獎勵」：某個享受只在做某件事時解鎖（例如某個 podcast 只在跑步時聽）。
- 只押代幣，不碰真錢。

**實作**
- 新事件：`contract_open`（目標、押金、期限）、`contract_settle`（由事件推算是否達成）；保護卡是商店物品，用掉時寫一筆 `streak_freeze` 事件。

### 8.3 隊友副本：讓別人看得到你
**依據**
- Quantic Foundry 玩家動機模型的六大類（行動、社交、精通、成就、沉浸、創造）中，Goal 目前主要滿足成就與精通，**社交（競爭、合作）完全沒有**。
- Talker Research：30% 的人在朋友或家人等著時才會開始做事；19% 會跟別人比賽來推自己一把。

**玩法**
- 用邀請碼找 1～3 個朋友（實驗室同學、刷題夥伴）組隊。
- 每週一隻共同 Boss，大家各自打卡就是傷害，週日結算團隊獎勵。
- 預設只顯示「今天有紀錄」與分類，不顯示細節；隱私自己決定。
- 「加油」按鈕：對方拿到少量代幣。
- 可與 8.2 結合：契約指定一位隊友當見證人。

**實作**
- 成本最高：Supabase 新增 `goal_parties`、`goal_party_members` 與共享進度的 view，RLS 只讓隊員看到彼此的摘要；公開註冊已關閉，所以用邀請碼加入。

**資料來源**
- Quantic Foundry – Gamer Motivation Model：https://quanticfoundry.com/?p=764
- YouGov – Bad habits Americans have：https://yougov.com/en-us/articles/44815-bad-habits-americans-have-yougov-poll
- Talker Research – What drives us to procrastinate：https://talkerresearch.com/study-finds-what-drives-us-to-procrastinate/
- Wharton – The fresh start effect：https://knowledge.wharton.upenn.edu/article/need-fresh-start-heres-begin/
- Duolingo – Streak research：https://blog.duolingo.com/duolingo-streak-research/
- Coglode – Temptation bundling：https://www.coglode.com/nuggets/temptation-bundling
