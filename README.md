# Goal 成就板 · 使用說明

## 開始使用
1. 雙擊 **`start.bat`**（需要 Python），會在 `http://localhost:8765` 打開 Goal，並可以「安裝」成桌面 App（網址列右邊的安裝圖示）。
   也可以照舊用 Chrome 或 Edge 直接打開 `index.html`，但這樣不能安裝成 App。
   - 第一次改用 start.bat 時，瀏覽器會當成新網站：按一次「連結 Goal 資料夾」，紀錄會從 `data/goal-data.json` 讀回來。
2. 按「連結 Goal 資料夾」，選這個 `Goal` 資料夾，允許讀寫。
   - 之後每次打開，如果瀏覽器要求，再按一次「繼續讀取資料夾」即可。
   - Firefox / Safari 不支援讀取資料夾，資料會存在瀏覽器裡，AI 判定可以用「貼上」匯入。
3. 在「今天」頁點習慣按鈕打卡，或手動記錄解題。

## 讓 AI 判題自動入帳（Claude Code）
1. 把 `ai-judge/judge.md` 複製到 `C:\Users\<你的帳號>\.claude\commands\judge.md`
   （放在這裡，任何資料夾都能用；只想在某個專案用，就放到該專案的 `.claude\commands\`）。
2. 在你寫 LeetCode 的資料夾開 Claude Code，輸入：
   ```
   /judge leetcode/0015_3sum.py
   ```
3. Claude Code 會實際跑測試和對拍，把結果寫進 `Goal/inbox/`。網頁開著的話，幾秒內就會入帳並跳出成就。

用網頁版聊天時：照 `ai-judge/chat-prompt.md` 的提示詞問，把 AI 的整段回覆貼到「設定 → 貼上 AI 判定」。

## 題單、每日任務、每週回顧
- **題單**分頁：你舊的進行中 31 題、收藏和筆記都在這裡；判題通過的題目會自動標成已解。點題名會打開 LeetCode。
- **今天**分頁的「今日任務」每天換 3 件事，全部完成 +20 XP、+25 代幣；「本週目標」全部達成 +50 XP、+80 代幣。
- **紀錄**分頁最上面是每週回顧，可以往前翻。

## 在 Claude App 判題
已存成 skill「leetcode-judge」：在 Claude App 貼上程式碼並說「幫我判題」，結果會寫進 inbox（連結電腦時）或給你一段可以貼上的 JSON。

## 測試一下
把 `inbox/_example.json` 複製一份，檔名改成 `example.json`（去掉開頭的 `_`），網頁會在 4 秒內讀到，並解鎖「第一題」徽章。

## 資料存在哪裡
- 連結資料夾後：`data/goal-data.json`（在 Google 雲端硬碟裡，會自動備份）。
- 同時也存一份在瀏覽器裡。
- 「設定 → 下載備份」可以隨時匯出。

## 惡習 Boss
「惡習」分頁：把想克制的事（半夜滑手機、手搖飲…）變成一隻有血量的 Boss。
- 每撐過一天沒犯，Boss 受 10 點傷害；想做又忍住時按「忍住了」，是一次暴擊（每天 2 次，有 XP 和代幣）。
- 犯了也照實按「犯了」：Boss 只回 20% 血，不會歸零。
- 會花錢的惡習可以記下這次省了多少，累計在「存錢罐」（真的新台幣）。
- 打倒後拿到大量獎勵，下一級更硬的 Boss 會接著出現。

## 其他 AI 也能記錄的事件
```json
{ "type": "habit", "habit": "exercise", "note": "跑步 5km" }
{ "type": "custom", "title": "幫學弟 debug", "cat": "life", "xp": 10, "coins": 5 }
{ "type": "restraint", "boss": "手搖飲", "amount": 60, "note": "路過飲料店沒買" }
{ "type": "relapse", "boss": "半夜滑手機" }
```
習慣 id 列在網頁的「設定 → 習慣」。custom 事件的獎勵上限是 30 XP、20 代幣。

規劃與之後的階段見 `PLAN.md`。
