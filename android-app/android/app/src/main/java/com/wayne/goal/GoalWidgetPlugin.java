package com.wayne.goal;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/** 網頁（index.html）與原生端的橋：登入狀態、widget 顯示資料、widget 打卡佇列、開外部連結。 */
@CapacitorPlugin(name = "GoalWidget")
public class GoalWidgetPlugin extends Plugin {

    @PluginMethod
    public void getSession(PluginCall call) {
        JSObject r = new JSObject();
        JSONObject s = GoalStore.getSession(getContext());
        if (s != null) r.put("session", s);
        call.resolve(r);
    }

    @PluginMethod
    public void setSession(PluginCall call) {
        JSObject s = call.getObject("session", null);
        GoalStore.setSession(getContext(), s);
        call.resolve();
    }

    @PluginMethod
    public void refreshSession(PluginCall call) {
        final boolean force = Boolean.TRUE.equals(call.getBoolean("force", false));
        new Thread(() -> {
            try {
                JSONObject s = GoalStore.refresh(getContext(), force);
                JSObject r = new JSObject();
                r.put("session", s);
                call.resolve(r);
            } catch (Exception e) {
                call.reject(e.getMessage() == null ? "refresh failed" : e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void updateWidget(PluginCall call) {
        JSObject summary = call.getObject("summary", null);
        if (summary != null) GoalStore.setSummary(getContext(), summary);
        GoalWidgetProvider.updateAll(getContext());
        call.resolve();
        new Thread(() -> GoalStore.flushQueue(getContext())).start();
    }

    @PluginMethod
    public void drainQueue(PluginCall call) {
        JSObject r = new JSObject();
        r.put("events", GoalStore.getQueue(getContext()));
        call.resolve(r);
    }

    @PluginMethod
    public void ackQueue(PluginCall call) {
        JSArray ids = call.getArray("ids", new JSArray());
        Set<String> set = new HashSet<>();
        for (int i = 0; i < ids.length(); i++) set.add(ids.optString(i));
        GoalStore.removeFromQueue(getContext(), set);
        call.resolve();
    }

    @PluginMethod
    public void checkUpdate(PluginCall call) {
        new Thread(() -> {
            try {
                call.resolve(JSObject.fromJSONObject(GoalUpdater.checkAndDownload(getContext())));
            } catch (Exception e) {
                call.reject(String.valueOf(e.getMessage()));
            }
        }).start();
    }

    @PluginMethod
    public void applyUpdate(PluginCall call) {
        String dir = GoalUpdater.installedDir(getContext());
        if (dir == null) { call.reject("no downloaded update"); return; }
        call.resolve();
        getActivity().runOnUiThread(() -> getBridge().setServerBasePath(dir));
    }

    @PluginMethod
    public void appInfo(PluginCall call) {
        JSObject r = new JSObject();
        r.put("nativeVersion", GoalUpdater.NATIVE_VERSION);
        r.put("bundledBuild", GoalUpdater.bundledBuild(getContext()));
        r.put("installedBuild", GoalUpdater.installedBuild(getContext()));
        call.resolve(r);
    }

    /** 震動：pattern 跟網頁的 navigator.vibrate 一樣是 [震, 停, 震, ...]（毫秒），強度逐段增加。 */
    @PluginMethod
    public void haptic(PluginCall call) {
        JSArray p = call.getArray("pattern", new JSArray());
        int n = Math.min(p.length(), 15);
        if (n == 0) { call.resolve(); return; }
        long[] timings = new long[n + 1];
        int[] amps = new int[n + 1];
        timings[0] = 0;
        amps[0] = 0;
        int pulse = 0;
        for (int i = 0; i < n; i++) {
            timings[i + 1] = Math.max(0, Math.min(1000, p.optLong(i)));
            boolean on = (i % 2 == 0);
            amps[i + 1] = on ? Math.min(255, 110 + 35 * pulse++) : 0;
        }
        Vibrator v;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) getContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            v = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (v == null || !v.hasVibrator()) { call.resolve(); return; }
        if (Build.VERSION.SDK_INT >= 26) {
            if (v.hasAmplitudeControl()) v.vibrate(VibrationEffect.createWaveform(timings, amps, -1));
            else v.vibrate(VibrationEffect.createWaveform(timings, -1));
        } else {
            v.vibrate(timings, -1);
        }
        call.resolve();
    }

    @PluginMethod
    public void openUrl(PluginCall call) {
        String url = call.getString("url");
        // 只開 https 連結，避免被用來開啟其他 App 的 intent 或本機檔案
        if (url == null || !url.startsWith("https://")) { call.reject("only https links are allowed"); return; }
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }
}
