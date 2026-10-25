package com.wayne.goal;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * App 自動更新：從 GitHub Pages 下載新版網頁到手機，交給 Capacitor 從那個資料夾載入。
 * - version.json 的 build 比目前大才下載；minNative 比這個 App 的 NATIVE_VERSION 大時代表要重新建置。
 * - 重新建置安裝（App 內建的網頁更新）時，若內建版本 >= 已下載版本，就改回用內建版本。
 */
final class GoalUpdater {
    static final String BASE = "https://wayne935.github.io/goal/";
    /** 原生程式（Java、小工具）的版本；改了原生程式才需要 +1。 */
    static final int NATIVE_VERSION = 3;

    private static final String PREFS = "goal_update";
    private static final String CAP_PREFS = "CapWebViewSettings";   // = com.getcapacitor.plugin.WebView.WEBVIEW_PREFS_NAME
    private static final String CAP_PATH = "serverBasePath";        // = com.getcapacitor.plugin.WebView.CAP_SERVER_PATH

    private GoalUpdater() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int bundledBuild(Context c) {
        try (InputStream in = c.getAssets().open("public/version.json")) {
            return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8)).optInt("build", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    static int installedBuild(Context c) {
        String dir = prefs(c).getString("dir", null);
        if (dir == null || !new File(dir, "index.html").exists()) return 0;
        return prefs(c).getInt("build", 0);
    }

    static int currentBuild(Context c) {
        return Math.max(bundledBuild(c), installedBuild(c));
    }

    static String installedDir(Context c) {
        return installedBuild(c) > 0 ? prefs(c).getString("dir", null) : null;
    }

    /** 在 Capacitor 載入網頁之前呼叫：內建版本較新（或一樣新）就不再用下載的版本。 */
    static void reconcile(Context c) {
        int installed = installedBuild(c);
        if (installed == 0 || bundledBuild(c) >= installed) {
            c.getSharedPreferences(CAP_PREFS, Context.MODE_PRIVATE).edit().remove(CAP_PATH).apply();
            prefs(c).edit().remove("dir").remove("build").apply();
            deleteRecursive(new File(c.getFilesDir(), "web"));
        }
    }

    /** 檢查並下載新版。回傳 {status: none|downloaded|incompatible|error, build, current, error}。 */
    static synchronized JSONObject checkAndDownload(Context c) {
        JSONObject r = new JSONObject();
        int current = currentBuild(c);
        GoalStore.put(r, "current", current);
        try {
            JSONObject v = new JSONObject(new String(get(BASE + "version.json?t=" + System.currentTimeMillis()), StandardCharsets.UTF_8));
            int build = v.optInt("build", 0);
            GoalStore.put(r, "build", build);
            if (build <= current) { GoalStore.put(r, "status", "none"); return r; }
            if (v.optInt("minNative", 1) > NATIVE_VERSION) { GoalStore.put(r, "status", "incompatible"); return r; }

            File root = new File(c.getFilesDir(), "web");
            File dir = new File(root, String.valueOf(build));
            deleteRecursive(dir);
            if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create " + dir);
            JSONArray files = v.optJSONArray("files");
            if (files == null || files.length() == 0) throw new IOException("version.json has no files");
            for (int i = 0; i < files.length(); i++) {
                String name = files.optString(i);
                if (name.isEmpty() || name.startsWith("/") || name.contains("..") || name.contains("\\")) continue;
                File out = new File(dir, name);
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
                byte[] data = get(BASE + name + "?t=" + System.currentTimeMillis());
                try (FileOutputStream fo = new FileOutputStream(out)) { fo.write(data); }
            }
            // 確認下載到的確實是這個 build 的頁面
            File index = new File(dir, "index.html");
            String html = index.exists() ? new String(readFile(index), StandardCharsets.UTF_8) : "";
            if (!html.contains("const APP_BUILD = " + build + ";")) {
                deleteRecursive(dir);
                throw new IOException("downloaded index.html is not build " + build + " (GitHub Pages may still be updating)");
            }
            c.getSharedPreferences(CAP_PREFS, Context.MODE_PRIVATE).edit().putString(CAP_PATH, dir.getAbsolutePath()).apply();
            prefs(c).edit().putString("dir", dir.getAbsolutePath()).putInt("build", build).apply();
            File[] old = root.listFiles();
            if (old != null) for (File f : old) if (!f.getName().equals(dir.getName())) deleteRecursive(f);
            GoalStore.put(r, "status", "downloaded");
        } catch (Exception e) {
            GoalStore.put(r, "status", "error");
            GoalStore.put(r, "error", String.valueOf(e.getMessage()));
        }
        return r;
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setConnectTimeout(10000);
            con.setReadTimeout(15000);
            con.setUseCaches(false);
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " for " + url);
            try (InputStream in = con.getInputStream()) { return readAll(in); }
        } finally {
            con.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private static byte[] readFile(File f) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f)) { return readAll(in); }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
