package com.wayne.goal;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;

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

    // ---------- 鎖手機（GuardState） ----------
    @PluginMethod
    public void guardStatus(PluginCall call) {
        new Thread(() -> call.resolve(JSObject.fromJSONObject(GuardState.status(getContext())))).start();
    }

    @PluginMethod
    public void guardConfig(PluginCall call) {
        JSObject cfg = call.getObject("config", null);
        if (cfg == null) { call.reject("missing config"); return; }
        GuardState.setConfig(getContext(), cfg);
        call.resolve();
    }

    @PluginMethod
    public void listApps(PluginCall call) {
        new Thread(() -> {
            JSObject r = new JSObject();
            r.put("apps", GuardState.listApps(getContext()));
            String sms = GuardState.defaultSms(getContext());
            if (sms != null) r.put("defaultSms", sms);
            call.resolve(r);
        }).start();
    }

    @PluginMethod
    public void focusStart(PluginCall call) {
        try {
            JSONObject s = GuardState.start(getContext(), call.getData());
            JSObject r = new JSObject();
            r.put("session", s);
            call.resolve(r);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void focusCancel(PluginCall call) {
        JSObject r = new JSObject();
        r.put("ok", GuardState.cancel(getContext()));
        call.resolve(r);
    }

    @PluginMethod
    public void focusAbort(PluginCall call) {
        String reason = call.getString("reason", "");
        if (reason == null || reason.trim().length() < 2) { call.reject("reason required"); return; }
        JSObject r = new JSObject();
        r.put("ok", GuardState.abort(getContext(), reason.trim()));
        call.resolve(r);
    }

    @PluginMethod
    public void openGuardSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    @PluginMethod
    public void requestNotify(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getActivity() != null) getActivity().requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 4101);
        call.resolve();
    }

    @PluginMethod
    public void consumeLaunch(PluginCall call) {
        JSObject r = new JSObject();
        String open = GuardState.prefs(getContext()).getString("launch", null);
        if (open != null) { r.put("open", open); GuardState.prefs(getContext()).edit().remove("launch").apply(); }
        call.resolve(r);
    }

    // ---------- Health Connect ----------
    @PluginMethod
    public void healthRequest(PluginCall call) {
        if (!HealthBridge.available(getContext())) { call.reject("unavailable"); return; }
        if (getActivity() != null) getActivity().requestPermissions(HealthBridge.PERMS, 4102);
        call.resolve();
    }

    @PluginMethod
    public void healthRead(PluginCall call) {
        final int days = call.getInt("days", 3);
        new Thread(() -> {
            try { call.resolve(JSObject.fromJSONObject(HealthBridge.read(getContext(), days))); }
            catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
        }).start();
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
