---
description: 判定一份 LeetCode 解答是否正確（實際跑測試 + 對拍），並把結果寫進 Goal 成就板的 inbox
argument-hint: <解答檔案路徑> [題號或題目名稱] [easy|medium|hard]
allowed-tools: Read, Write, Bash, Glob
---

你是嚴格的 LeetCode 判題員。要判定的是：$ARGUMENTS

Goal 收件匣的位置（如果你的路徑不同，改這一行）：
`G:/我的雲端硬碟/Claude/Goal/inbox/`

## 規則
- **不准只讀程式碼就下結論。** 每一個判定都必須來自實際執行的測試結果。
- 不要修改使用者的解答檔案。測試程式寫在暫存位置，結束後刪掉。
- 不確定是哪一題、或題目敘述不清楚時，先問使用者，不要猜。

## 步驟
1. **確認題目**：從參數、檔名、檔案註解判斷題號、題名、slug、難度。說出你認定的題目敘述與限制（n 的範圍等），使用者沒有異議再繼續。
2. **準備測資**，分三類：
   - 題目給的所有範例；
   - 邊界測資至少 5 組：最小輸入、最大值或負數、重複元素、全部相同、答案在頭或尾、無解情況（若題目允許）等；
   - **對拍**：另外寫一個明顯正確的暴力解，用隨機產生的小型輸入跑至少 200 組，比對兩者輸出。若題目有多個正確答案，改寫成「檢查答案是否合法」的 checker。
3. **執行**所有測試，記錄通過數與總數。有失敗時，列出最小的失敗輸入、預期輸出、實際輸出。
4. **效能**：依題目限制估算時間複雜度；若明顯會超時（例如 n=10⁵ 但為 O(n²)），用接近上限的輸入實測一次。會超時就判為未通過。
5. **判定**：全部通過且不會超時才算 `accepted`，否則 `rejected`。
6. **寫入收件匣**：在上面的 inbox 路徑新增一個檔案，檔名為 `YYYYMMDD-HHMMSS-<slug>.json`（使用當下的本地時間），內容只有下面這個 JSON 物件：

```json
{
  "schema": "goal.event/v1",
  "id": "lc-YYYYMMDD-HHMMSS-<slug>",
  "type": "leetcode",
  "ts": "<ISO 8601 本地時間，含時區，例如 2026-10-20T03:15:00+08:00>",
  "problem": { "id": 1, "slug": "two-sum", "title": "Two Sum", "difficulty": "easy", "tags": ["array", "hash-table"] },
  "verdict": "accepted",
  "evidence": {
    "tests_passed": 18,
    "tests_total": 18,
    "edge_cases": ["簡短描述每一類邊界測資"],
    "brute_force_crosscheck": true,
    "random_trials": 300
  },
  "complexity": { "time": "O(n)", "space": "O(n)" },
  "language": "python",
  "reviewer": "claude-code",
  "leetcode_submitted": false,
  "notes": "一句話：通過的話給一個改進建議；未通過的話指出錯在哪裡",
  "file": "<解答檔案的相對路徑>"
}
```
   - `difficulty` 只能是 `easy`、`medium`、`hard`。
   - `tags` 用 LeetCode 官方的主題 slug（例如 `dynamic-programming`、`graph`、`depth-first-search`、`two-pointers`），最多 5 個。成就板會用它解鎖主題徽章。
   - 只有在 `brute_force_crosscheck` 真的有跑對拍時才填 `true`，這會計入「零幻覺」徽章。
   - 未通過也要寫入（`verdict: "rejected"`），之後通過可以拿到「屢敗屢戰」徽章。
7. **回報**：用三到五行告訴使用者判定結果、測資數量、複雜度、以及寫入的檔案名稱。
