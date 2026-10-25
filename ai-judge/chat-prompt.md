# 網頁聊天版判題提示詞

在 claude.ai（或任何能執行程式的 AI 聊天）貼上這段，再附上你的解答。
AI 回覆後，把整段回覆貼到 Goal 成就板「設定 → 貼上 AI 判定」，網頁會自動找出 JSON。

---

請幫我判定這份 LeetCode 解答是否正確。

題目：（題號 + 題名，例如 15. 3Sum）
難度：（easy / medium / hard）

```
（貼上你的程式碼）
```

要求：
1. 不要只看程式碼下結論，請實際執行測試：題目範例、至少 5 組邊界測資、以及用暴力解對拍至少 200 組隨機小型輸入。
2. 依題目限制判斷是否會超時，會超時就算未通過。
3. 全部通過且不會超時才算 accepted，否則 rejected，並指出最小的失敗輸入。
4. tags 請用 LeetCode 官方主題 slug。
5. 最後輸出一個 JSON 程式碼區塊，格式如下（ts 用台灣時間）：

```json
{
  "schema": "goal.event/v1",
  "type": "leetcode",
  "ts": "2026-10-20T03:15:00+08:00",
  "problem": { "id": 15, "slug": "3sum", "title": "3Sum", "difficulty": "medium", "tags": ["array", "two-pointers", "sorting"] },
  "verdict": "accepted",
  "evidence": { "tests_passed": 0, "tests_total": 0, "edge_cases": [], "brute_force_crosscheck": true, "random_trials": 0 },
  "complexity": { "time": "", "space": "" },
  "language": "python",
  "reviewer": "claude-chat",
  "notes": ""
}
```
