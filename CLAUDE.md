# Goal 成就板：發佈 repo

這個 repo 是 Goal 網頁的發佈目的地（GitHub Pages：https://wayne935.github.io/goal/），也是 Android App 自動更新的來源。

- 原始檔在使用者電腦的 Goal 資料夾（`G:\我的雲端硬碟\Claude\Goal`），先改那裡，再把 `index.html`、`manifest.json`、`sw.js`、`version.json`、`icons/` 複製到這裡推上 main。
- 每次修改 `index.html`：`const APP_BUILD = N;` 要 +1，`version.json` 的 `build` 要改成同一個數字。App 會比對這兩個值，不一致就不會更新。
- 只有改了 Android 原生程式（`android-app/` 裡的 Java、小工具）時，才把 `version.json` 的 `minNative` 改成新的 `GoalUpdater.NATIVE_VERSION`，並請使用者重新建置 App。
- 不要把任何個人資料（Email、紀錄、`data/`、`inbox/`）放進這個公開 repo。
