# Goal Android App 與桌面小工具

這個資料夾是 Goal 的 Android App（Capacitor 8.5.3），App 裡面就是你電腦上的同一個 `index.html`，另外加了一個桌面小工具。

## 安裝前（只要做一次）
- Node.js 24 LTS、Android Studio 已安裝並完成第一次的 Setup Wizard。
- **筆電先登入雲端同步**：用 `start.bat` 打開 Goal → 設定 → 雲端同步 → 輸入密碼登入。這樣你目前的紀錄會先上傳，手機一登入就能拿到全部資料。

## 建置並安裝到 Pixel 9a
1. 雙擊 `build-android.bat`。它會：
   - 把專案複製到 `C:\Users\<你的帳號>\goal-android`（在雲端硬碟外面建置，避免幾千個暫存檔被同步）；
   - 放入最新的 `index.html`；
   - 第一次會執行 `npm install`（約 1 分鐘）；
   - 執行 `npx cap sync android`，然後打開 Android Studio。
2. Android Studio 第一次開啟這個專案會自動「Gradle Sync」，下載建置工具，大約 3 到 10 分鐘，右下角有進度條。
   - 如果跳出建議升級 Android Gradle Plugin 的提示，先選不要升級。
3. 手機用 USB 接上筆電（USB 偵錯已開），上方工具列的裝置選單選 **Pixel 9a**，按綠色 ▶ **Run**。
4. App 會自動裝到手機並打開。到「設定 → 雲端同步」輸入密碼登入，紀錄會下載下來。

## 加入桌面小工具
1. 在手機桌面空白處長按 → **小工具**。
2. 找到 **Goal**，把「今日進度與一鍵打卡」拖到桌面。
3. 小工具上方顯示等級、經驗條、連續天數、代幣、今日 XP 與任務；下方三個按鈕一按就記錄一次。
   - 點上方區域會打開 App。
   - 三個按鈕要放哪些習慣，在 App 的「設定 → 偏好 → 手機小工具按鈕」選。
   - 小工具記錄的事會直接上傳雲端；沒網路時先存在手機，下次有網路或打開 App 時補上傳。

## 之後更新 App
- **網頁功能更新：自動。** App 每次打開會到 `https://wayne935.github.io/goal/version.json` 檢查，有新的 build 就下載，跳出「立即更新」提示；不按的話下次開啟時生效。也可以在「設定 → 偏好 → 檢查更新」手動檢查。
- **原生程式更新（小工具、Java）才需要重新建置：** App 會提示「新版本需要重新建置 App」，這時再雙擊 `build-android.bat`，在 Android Studio 按 ▶ Run。資料都在雲端，重裝不會遺失。

- **震動（build 4 起）：** 網頁更新會自動送到手機，但手機原生的震動波形（升級、徽章、打卡）需要重新建置一次 App 才有；沒重建之前，升級動畫照常播放，只是震動較弱或沒有。震動可在 App 的「設定 → 偏好 → 震動」關閉。

## 出問題時
- `build-android.bat` 視窗出現錯誤：整段複製給 Claude。
- Android Studio 建置失敗：打開下方 **Build** 分頁，複製紅色的錯誤訊息給 Claude。
- App 打開是空白或舊版：重新執行 `build-android.bat` 再 Run。

## 檔案說明
| 路徑 | 內容 |
|---|---|
| `android/app/src/main/java/com/wayne/goal/GoalWidgetProvider.java` | 桌面小工具的畫面與按鈕 |
| `android/app/src/main/java/com/wayne/goal/GoalStore.java` | 登入狀態、小工具佇列、上傳到 Supabase |
| `android/app/src/main/java/com/wayne/goal/GoalWidgetPlugin.java` | App 網頁與原生端之間的橋 |
| `android/app/src/main/java/com/wayne/goal/GoalUpdater.java` | 自動更新：下載新版網頁並切換 |
| `android/app/src/main/res/layout/goal_widget.xml` | 小工具版面 |
