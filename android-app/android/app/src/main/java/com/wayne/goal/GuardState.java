package com.wayne.goal;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * 鎖手機的狀態與規則（專注、宵禁、分心 App 放行）。
 *
 * - 專注：只能用白名單裡的 App，時間到自動寫一筆 focus 事件（result=done）進 GoalStore 佇列並上傳雲端。
 * - 宵禁：每天固定時段只能用白名單；守住一整晚 = focus 事件（kind=curfew, result=kept）。
 * - 分心 App：預設上鎖，呼吸 + 寫原因 + 選時間才放行，時間到自動鎖回去（參考 ClaryMind）。
 * - 緊急解鎖：隨時可以，寫一筆 result=aborted，扣多少代幣由網頁 compute() 依事件推算。
 * - 守護服務（無障礙服務）有開著的時段記在 up 區間；專注或宵禁期間關掉超過 1 分鐘就不給獎勵。
 */
final class GuardState {
    private static final String PREFS = "goal_guard";
    static final long CANCEL_MS = 2 * 60_000L;          // 開始後 2 分鐘內取消不罰
    static final long TAMPER_GRACE_MS = 60_000L;         // 守護服務最多可以斷 1 分鐘（例如 App 更新）
    static final String CH_FOCUS = "goal_focus", CH_GOAL = "goal_main";
    static final int N_FOCUS = 41, N_DONE = 42, N_REMIND = 43, N_CURFEW = 44;

    /** 任何模式都不擋：系統介面、權限視窗、電話與緊急求救、時鐘（鬧鐘要能響）。 */
    private static final Set<String> ALWAYS = new HashSet<>(Arrays.asList(
            "android", "com.android.systemui", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.phone", "com.android.server.telecom", "com.android.dialer", "com.google.android.dialer",
            "com.android.emergency", "com.google.android.apps.safetyhub", "com.android.incallui",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.google.android.deskclock", "com.android.deskclock"));

    enum Mode { NONE, FOCUS, CURFEW }

    private GuardState() {}

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONObject obj(String s) {
        if (s == null) return null;
        try { return new JSONObject(s); } catch (JSONException e) { return null; }
    }

    private static JSONArray arr(String s) {
        if (s == null) return new JSONArray();
        try { return new JSONArray(s); } catch (JSONException e) { return new JSONArray(); }
    }

    static Set<String> strSet(JSONArray a) {
        Set<String> out = new HashSet<>();
        if (a != null) for (int i = 0; i < a.length(); i++) { String s = a.optString(i, ""); if (!s.isEmpty()) out.add(s); }
        return out;
    }

    // ---------- 設定（網頁送來） ----------
    static synchronized JSONObject config(Context c) {
        JSONObject o = obj(prefs(c).getString("config", null));
        return o != null ? o : new JSONObject();
    }

    static synchronized void setConfig(Context c, JSONObject cfg) {
        prefs(c).edit().putString("config", cfg.toString()).putInt("coins_adj", 0).apply();
        scheduleAll(c);
    }

    static int dayStartHour(Context c) { return config(c).optInt("dayStartHour", 4); }

    static JSONObject gateCfg(Context c) {
        JSONObject g = config(c).optJSONObject("gate");
        return g != null ? g : new JSONObject();
    }

    static JSONObject curfewCfg(Context c) {
        JSONObject g = config(c).optJSONObject("curfew");
        return g != null ? g : new JSONObject();
    }

    /** 網頁最後一次送來的代幣餘額，扣掉之後在手機上加買放行花掉的。 */
    static synchronized int coins(Context c) {
        return config(c).optInt("coins", 0) - prefs(c).getInt("coins_adj", 0);
    }

    // ---------- 守護服務有開著的時段 ----------
    static boolean serviceEnabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) return false;
        ComponentName me = new ComponentName(c, GuardService.class);
        for (String part : s.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(part);
            if (me.equals(cn)) return true;
        }
        return false;
    }

    static synchronized void markUp(Context c) {
        JSONArray a = arr(prefs(c).getString("up", null));
        long now = System.currentTimeMillis();
        JSONArray last = a.length() > 0 ? a.optJSONArray(a.length() - 1) : null;
        if (last != null && last.optLong(1) == 0) return;   // 已經記成開著
        JSONArray it = new JSONArray(); it.put(now); it.put(0L);
        a.put(it);
        JSONArray out = new JSONArray();
        for (int i = Math.max(0, a.length() - 30); i < a.length(); i++) out.put(a.opt(i));
        prefs(c).edit().putString("up", out.toString()).apply();
    }

    static synchronized void markDown(Context c) {
        JSONArray a = arr(prefs(c).getString("up", null));
        if (a.length() == 0) return;
        JSONArray last = a.optJSONArray(a.length() - 1);
        if (last != null && last.optLong(1) == 0) {
            try { last.put(1, System.currentTimeMillis()); } catch (JSONException ignored) { }
            prefs(c).edit().putString("up", a.toString()).apply();
        }
    }

    /** [s, e) 之間守護服務開著的毫秒數。 */
    static synchronized long coverage(Context c, long s, long e) {
        JSONArray a = arr(prefs(c).getString("up", null));
        long now = System.currentTimeMillis(), sum = 0;
        boolean on = serviceEnabled(c);
        for (int i = 0; i < a.length(); i++) {
            JSONArray it = a.optJSONArray(i);
            if (it == null) continue;
            long st = it.optLong(0), en = it.optLong(1);
            if (en == 0) en = on ? now : st;
            sum += Math.max(0, Math.min(en, e) - Math.max(st, s));
        }
        return sum;
    }

    private static boolean guarded(Context c, long s, long e) {
        return coverage(c, s, e) >= (e - s) - TAMPER_GRACE_MS;
    }

    // ---------- 專注 ----------
    static synchronized JSONObject session(Context c) {
        return obj(prefs(c).getString("session", null));
    }

    private static void setSession(Context c, JSONObject s) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (s == null) e.remove("session"); else e.putString("session", s.toString());
        e.apply();
    }

    /** req：habit、name、icon、minutes、units、apps[]、penalty、xp、coins。回傳開始的專注。 */
    static synchronized JSONObject start(Context c, JSONObject req) throws Exception {
        if (session(c) != null) throw new Exception("已經在專注中");
        if (!serviceEnabled(c)) throw new Exception("guard_off");
        int minutes = Math.max(1, Math.min(240, req.optInt("minutes", 25)));
        long now = System.currentTimeMillis();
        JSONObject s = new JSONObject();
        GoalStore.put(s, "id", "fo-" + stamp(now) + "-" + Integer.toHexString(new Random().nextInt(0x10000)));
        GoalStore.put(s, "habit", req.optString("habit"));
        GoalStore.put(s, "name", req.optString("name", "專注"));
        GoalStore.put(s, "icon", req.optString("icon", "🎯"));
        GoalStore.put(s, "minutes", minutes);
        GoalStore.put(s, "units", Math.max(1, Math.min(12, req.optInt("units", 1))));
        GoalStore.put(s, "apps", req.optJSONArray("apps") != null ? req.optJSONArray("apps") : new JSONArray());
        GoalStore.put(s, "penalty", req.optInt("penalty", 0));
        GoalStore.put(s, "xp", req.optInt("xp", 0));
        GoalStore.put(s, "coins", req.optInt("coins", 0));
        GoalStore.put(s, "start", now);
        GoalStore.put(s, "end", now + minutes * 60_000L);
        setSession(c, s);
        markUp(c);
        scheduleAll(c);
        postFocusOngoing(c, s);
        return s;
    }

    private static JSONObject sessionEvent(JSONObject s, String result, long at, String reason) {
        JSONObject ev = new JSONObject();
        GoalStore.put(ev, "id", s.optString("id"));
        GoalStore.put(ev, "type", "focus");
        GoalStore.put(ev, "kind", "session");
        GoalStore.put(ev, "habit", s.optString("habit"));
        GoalStore.put(ev, "minutes", s.optInt("minutes"));
        GoalStore.put(ev, "units", s.optInt("units", 1));
        GoalStore.put(ev, "start", iso(s.optLong("start")));
        GoalStore.put(ev, "ts", iso(at));
        GoalStore.put(ev, "result", result);
        GoalStore.put(ev, "done", (int) Math.max(0, Math.min(s.optInt("minutes"), (at - s.optLong("start")) / 60_000L)));
        if (reason != null && !reason.isEmpty()) GoalStore.put(ev, "reason", reason.length() > 200 ? reason.substring(0, 200) : reason);
        GoalStore.put(ev, "source", "focus");
        return ev;
    }

    static synchronized boolean canCancel(Context c) {
        JSONObject s = session(c);
        return s != null && System.currentTimeMillis() - s.optLong("start") < CANCEL_MS;
    }

    /** 開始後 2 分鐘內取消：不扣分也不給獎勵。 */
    static synchronized boolean cancel(Context c) {
        JSONObject s = session(c);
        if (s == null || !canCancel(c)) return false;
        emit(c, sessionEvent(s, "cancelled", System.currentTimeMillis(), null));
        endSession(c);
        return true;
    }

    /** 緊急解鎖：專注中就中止專注；宵禁中就放行到這次宵禁結束。回傳是否有東西被解鎖。 */
    static synchronized boolean abort(Context c, String reason) {
        long now = System.currentTimeMillis();
        JSONObject s = session(c);
        if (s != null) {
            if (now >= s.optLong("end")) { settle(c); return true; }
            emit(c, sessionEvent(s, "aborted", now, reason));
            endSession(c);
            return true;
        }
        long[] w = curfewWindowAt(c, now);
        if (w != null && !curfewOpen(c, now)) {
            String night = nightKey(c, w[0]);
            emit(c, curfewEvent(c, night, w[0], "aborted", reason, now));
            markCurfew(c, night, "aborted");
            prefs(c).edit().putLong("cf_open_until", w[1]).apply();
            return true;
        }
        return false;
    }

    private static void endSession(Context c) {
        setSession(c, null);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(N_FOCUS);
        scheduleAll(c);
    }

    /** 把時間到了的專注、結束了的宵禁記下來。可以重複呼叫。 */
    static synchronized void settle(Context c) {
        long now = System.currentTimeMillis();
        JSONObject s = session(c);
        if (s != null && now >= s.optLong("end")) {
            long st = s.optLong("start"), en = s.optLong("end");
            if (guarded(c, st, en)) {
                emit(c, sessionEvent(s, "done", en, null));
                notifyDone(c, s.optString("icon") + " " + s.optString("name") + " 完成！",
                        "專注 " + s.optInt("minutes") + " 分鐘，+" + s.optInt("xp") + " XP、+" + s.optInt("coins") + " 代幣已自動入帳");
            } else {
                emit(c, sessionEvent(s, "aborted", en, "專注途中關閉了守護服務"));
                notifyDone(c, "這次專注沒有算分", "專注途中守護服務被關閉超過 1 分鐘，視同緊急解鎖。");
            }
            endSession(c);
        }
        settleCurfew(c, now);
    }

    // ---------- 宵禁 ----------
    private static int[] hm(String v, int dh, int dm) {
        try {
            String[] p = v.split(":");
            return new int[]{Math.max(0, Math.min(23, Integer.parseInt(p[0]))), Math.max(0, Math.min(59, Integer.parseInt(p[1])))};
        } catch (Exception e) { return new int[]{dh, dm}; }
    }

    /** 從 dayOffset 天前（相對今天的日曆日期）開始的那一次宵禁 [開始, 結束]。 */
    private static long[] curfewWindow(Context c, int dayOffset) {
        JSONObject cf = curfewCfg(c);
        int[] a = hm(cf.optString("start", "01:00"), 1, 0), b = hm(cf.optString("end", "07:00"), 7, 0);
        Calendar s = Calendar.getInstance();
        s.add(Calendar.DAY_OF_MONTH, -dayOffset);
        s.set(Calendar.HOUR_OF_DAY, a[0]); s.set(Calendar.MINUTE, a[1]); s.set(Calendar.SECOND, 0); s.set(Calendar.MILLISECOND, 0);
        Calendar e = (Calendar) s.clone();
        e.set(Calendar.HOUR_OF_DAY, b[0]); e.set(Calendar.MINUTE, b[1]);
        if (!e.after(s)) e.add(Calendar.DAY_OF_MONTH, 1);
        return new long[]{s.getTimeInMillis(), e.getTimeInMillis()};
    }

    static boolean curfewOn(Context c) { return curfewCfg(c).optBoolean("on", false); }

    /** now 落在哪一次宵禁裡（沒開或不在時段內回傳 null）。 */
    static long[] curfewWindowAt(Context c, long now) {
        if (!curfewOn(c)) return null;
        for (int off = 1; off >= -1; off--) {
            long[] w = curfewWindow(c, off);
            if (now >= w[0] && now < w[1]) return w;
        }
        return null;
    }

    static boolean curfewOpen(Context c, long now) {
        return now < prefs(c).getLong("cf_open_until", 0);
    }

    static String nightKey(Context c, long startMs) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(startMs);
        cal.add(Calendar.HOUR_OF_DAY, -dayStartHour(c));
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static JSONObject curfewEvent(Context c, String night, long startMs, String result, String reason, long at) {
        JSONObject cf = curfewCfg(c), ev = new JSONObject();
        GoalStore.put(ev, "id", "cf-" + night + ("kept".equals(result) ? "" : "-x"));
        GoalStore.put(ev, "type", "focus");
        GoalStore.put(ev, "kind", "curfew");
        GoalStore.put(ev, "habit", cf.optString("habit", "bedtime"));
        GoalStore.put(ev, "night", night);
        // 守住的紀錄放在宵禁開始的時間，才會算進前一天（凌晨 1 點前上床那天）
        GoalStore.put(ev, "ts", iso("kept".equals(result) ? startMs + 60_000L : at));
        GoalStore.put(ev, "result", result);
        if (reason != null && !reason.isEmpty()) GoalStore.put(ev, "reason", reason.length() > 200 ? reason.substring(0, 200) : reason);
        GoalStore.put(ev, "source", "focus");
        return ev;
    }

    private static JSONObject curfewLog(Context c) {
        JSONObject o = obj(prefs(c).getString("cf_log", null));
        return o != null ? o : new JSONObject();
    }

    private static void markCurfew(Context c, String night, String v) {
        JSONObject o = curfewLog(c);
        GoalStore.put(o, night, v);
        // 只留最近 20 晚
        if (o.length() > 20) {
            String min = null;
            for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); if (min == null || k.compareTo(min) < 0) min = k; }
            if (min != null) o.remove(min);
        }
        prefs(c).edit().putString("cf_log", o.toString()).apply();
    }

    private static void settleCurfew(Context c, long now) {
        if (!curfewOn(c)) return;
        long since = curfewCfg(c).optLong("since", 0);
        for (int off = 2; off >= 0; off--) {
            long[] w = curfewWindow(c, off);
            if (w[1] > now || w[0] < since) continue;          // 還沒結束，或開啟宵禁之前的晚上
            if (now - w[1] > 36 * 3600_000L) continue;         // 太久以前的不補
            String night = nightKey(c, w[0]);
            if (curfewLog(c).has(night)) continue;
            if (guarded(c, w[0], w[1])) {
                emit(c, curfewEvent(c, night, w[0], "kept", null, now));
                markCurfew(c, night, "kept");
            } else markCurfew(c, night, "off");
        }
    }

    // ---------- 現在是什麼模式、哪些 App 可以用 ----------
    static synchronized Mode mode(Context c, long now) {
        JSONObject s = session(c);
        if (s != null && now < s.optLong("end")) return Mode.FOCUS;
        if (curfewWindowAt(c, now) != null && !curfewOpen(c, now)) return Mode.CURFEW;
        return Mode.NONE;
    }

    private static String homeCache = null;
    private static long homeAt = 0;
    private static Set<String> imeCache = null;

    static String homePkg(Context c) {
        long now = System.currentTimeMillis();
        if (homeCache != null && now - homeAt < 60_000L) return homeCache;
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = c.getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        homeCache = ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : "";
        homeAt = now;
        return homeCache;
    }

    static boolean systemAllowed(Context c, String pkg) {
        if (pkg == null || pkg.equals(c.getPackageName()) || ALWAYS.contains(pkg) || pkg.equals(homePkg(c))) return true;
        try {
            TelecomManager tm = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
            if (tm != null && pkg.equals(tm.getDefaultDialerPackage())) return true;
        } catch (Exception ignored) { }
        if (imeCache == null) {
            imeCache = new HashSet<>();
            InputMethodManager im = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) for (InputMethodInfo info : im.getEnabledInputMethodList()) imeCache.add(info.getPackageName());
        }
        return imeCache.contains(pkg);
    }

    static void resetCaches() { imeCache = null; homeCache = null; }

    /** 專注或宵禁時這個 App 能不能用。 */
    static boolean allowed(Context c, String pkg, Mode m) {
        if (systemAllowed(c, pkg)) return true;
        if (strSet(config(c).optJSONArray("allow")).contains(pkg)) return true;
        if (m == Mode.FOCUS) {
            JSONObject s = session(c);
            return s != null && strSet(s.optJSONArray("apps")).contains(pkg);
        }
        return false;
    }

    static boolean isDistracting(Context c, String pkg) {
        return pkg != null && strSet(config(c).optJSONArray("distract")).contains(pkg);
    }

    // ---------- 分心 App 放行（ClaryMind 式） ----------
    static boolean gateOpen(Context c, long now) { return now < prefs(c).getLong("gate_until", 0); }

    static long gateUntil(Context c) { return prefs(c).getLong("gate_until", 0); }

    private static void rollGateDay(Context c) {
        String today = GoalStore.dayKey(dayStartHour(c));
        if (!today.equals(prefs(c).getString("gate_day", ""))) prefs(c).edit().putString("gate_day", today).putInt("gate_used", 0).apply();
    }

    static synchronized int gateLeft(Context c) {
        rollGateDay(c);
        return Math.max(0, gateCfg(c).optInt("passes", 3) - prefs(c).getInt("gate_used", 0));
    }

    /** 放行 minutes 分鐘；paid > 0 是用代幣加買的一次。 */
    static synchronized void openGate(Context c, int minutes, String reason, String app, int paid) {
        rollGateDay(c);
        long now = System.currentTimeMillis();
        minutes = Math.max(1, Math.min(60, minutes));
        SharedPreferences.Editor e = prefs(c).edit().putLong("gate_until", now + minutes * 60_000L);
        if (paid > 0) e.putInt("coins_adj", prefs(c).getInt("coins_adj", 0) + paid);
        else e.putInt("gate_used", prefs(c).getInt("gate_used", 0) + 1);
        e.apply();
        JSONObject ev = new JSONObject();
        GoalStore.put(ev, "id", "gt-" + stamp(now) + "-" + Integer.toHexString(new Random().nextInt(0x10000)));
        GoalStore.put(ev, "type", "gate");
        GoalStore.put(ev, "ts", iso(now));
        GoalStore.put(ev, "app", app == null ? "" : app);
        GoalStore.put(ev, "minutes", minutes);
        GoalStore.put(ev, "reason", reason == null ? "" : (reason.length() > 120 ? reason.substring(0, 120) : reason));
        GoalStore.put(ev, "paid", paid);
        GoalStore.put(ev, "source", "focus");
        emit(c, ev);
    }

    /** 在放行畫面選了「算了，不開了」。 */
    static synchronized void skipGate(Context c, String app) {
        long now = System.currentTimeMillis();
        JSONObject ev = new JSONObject();
        GoalStore.put(ev, "id", "gt-" + stamp(now) + "-" + Integer.toHexString(new Random().nextInt(0x10000)));
        GoalStore.put(ev, "type", "gate");
        GoalStore.put(ev, "ts", iso(now));
        GoalStore.put(ev, "app", app == null ? "" : app);
        GoalStore.put(ev, "minutes", 0);
        GoalStore.put(ev, "skipped", true);
        GoalStore.put(ev, "source", "focus");
        emit(c, ev);
    }

    // ---------- 寫事件 ----------
    static void emit(Context c, JSONObject ev) {
        GoalStore.enqueue(c, ev);
        final Context app = c.getApplicationContext();
        new Thread(() -> GoalStore.flushQueue(app)).start();
    }

    static String stamp(long ms) { return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(ms)); }

    static String iso(long ms) { return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date(ms)); }

    static String hhmm(long ms) { return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ms)); }

    // ---------- 狀態（給網頁） ----------
    static synchronized JSONObject status(Context c) {
        settle(c);
        long now = System.currentTimeMillis();
        JSONObject r = new JSONObject();
        GoalStore.put(r, "guard", serviceEnabled(c));
        GoalStore.put(r, "notify", Build.VERSION.SDK_INT < 33 || c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        GoalStore.put(r, "exact", Build.VERSION.SDK_INT < 31 || (am != null && am.canScheduleExactAlarms()));
        GoalStore.put(r, "health", HealthBridge.status(c));
        JSONObject s = session(c);
        if (s != null) { GoalStore.put(s, "canCancel", canCancel(c)); GoalStore.put(r, "session", s); }
        GoalStore.put(r, "mode", mode(c, now).name().toLowerCase(Locale.US));
        long[] w = curfewWindowAt(c, now);
        if (w != null) { GoalStore.put(r, "curfewEnd", w[1]); GoalStore.put(r, "curfewOpen", curfewOpen(c, now)); }
        GoalStore.put(r, "gateLeft", gateLeft(c));
        GoalStore.put(r, "gateUntil", gateUntil(c));
        GoalStore.put(r, "now", now);
        return r;
    }

    // ---------- 鬧鐘：專注結束、晚上提醒、宵禁前提醒、宵禁結束 ----------
    private static PendingIntent alarmIntent(Context c, String action, int code) {
        Intent i = new Intent(c, GuardReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void setAlarm(Context c, String action, int code, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = alarmIntent(c, action, code);
        if (at <= 0) { am.cancel(pi); return; }
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static long nextAt(String hhmm, long after) {
        int[] t = hm(hhmm, 21, 30);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(after);
        cal.set(Calendar.HOUR_OF_DAY, t[0]); cal.set(Calendar.MINUTE, t[1]); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= after) cal.add(Calendar.DAY_OF_MONTH, 1);
        return cal.getTimeInMillis();
    }

    static synchronized void scheduleAll(Context c) {
        long now = System.currentTimeMillis();
        JSONObject s = session(c);
        setAlarm(c, GuardReceiver.FOCUS_END, 1, s != null ? s.optLong("end") + 1000 : 0);
        JSONObject rm = config(c).optJSONObject("reminder");
        setAlarm(c, GuardReceiver.REMIND, 2, rm != null && rm.optBoolean("on", false) ? nextAt(rm.optString("time", "21:30"), now) : 0);
        if (curfewOn(c)) {
            long warn = 0, end = 0;
            for (int off = 1; off >= -1; off--) {
                long[] w = curfewWindow(c, off);
                if (warn == 0 && w[0] - 10 * 60_000L > now) warn = w[0] - 10 * 60_000L;
                if (end == 0 && w[1] > now) end = w[1] + 5000;
            }
            setAlarm(c, GuardReceiver.CURFEW_WARN, 3, warn);
            setAlarm(c, GuardReceiver.CURFEW_END, 4, end);
        } else {
            setAlarm(c, GuardReceiver.CURFEW_WARN, 3, 0);
            setAlarm(c, GuardReceiver.CURFEW_END, 4, 0);
        }
    }

    // ---------- 通知 ----------
    private static NotificationManager nm(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(CH_FOCUS) == null)
                nm.createNotificationChannel(new NotificationChannel(CH_FOCUS, "專注倒數", NotificationManager.IMPORTANCE_LOW));
            if (nm.getNotificationChannel(CH_GOAL) == null)
                nm.createNotificationChannel(new NotificationChannel(CH_GOAL, "完成與提醒", NotificationManager.IMPORTANCE_DEFAULT));
        }
        return nm;
    }

    private static boolean canNotify(Context c) {
        return Build.VERSION.SDK_INT < 33 || c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
    }

    static PendingIntent openApp(Context c, String open) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (open != null) i.putExtra("goal_open", open);
        return PendingIntent.getActivity(c, open == null ? 10 : open.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Notification.Builder builder(Context c, String ch) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, ch) : new Notification.Builder(c);
        return b.setSmallIcon(R.mipmap.ic_launcher).setAutoCancel(true);
    }

    static void postFocusOngoing(Context c, JSONObject s) {
        NotificationManager n = nm(c);
        if (n == null || !canNotify(c)) return;
        Notification.Builder b = builder(c, CH_FOCUS)
                .setContentTitle("專注中：" + s.optString("icon") + " " + s.optString("name"))
                .setContentText(GuardState.hhmm(s.optLong("end")) + " 結束，時間到自動入帳")
                .setOngoing(true).setAutoCancel(false)
                .setWhen(s.optLong("end")).setShowWhen(true).setUsesChronometer(true)
                .setContentIntent(openApp(c, "focus"));
        if (Build.VERSION.SDK_INT >= 24) b.setChronometerCountDown(true);
        n.notify(N_FOCUS, b.build());
    }

    static void notifyDone(Context c, String title, String text) {
        NotificationManager n = nm(c);
        if (n == null || !canNotify(c)) return;
        n.notify(N_DONE, builder(c, CH_GOAL).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(openApp(c, null)).build());
    }

    /** 晚上提醒：今天還沒紀錄時強調「保住連續天數」，有紀錄了就提醒補登和每日任務。 */
    static void notifyReminder(Context c) {
        NotificationManager n = nm(c);
        if (n == null || !canNotify(c)) return;
        JSONObject s = GoalStore.currentSummary(c);
        int xp = s.optInt("todayXp"), streak = s.optInt("streak"), freezes = s.optInt("freezes");
        boolean active = s.optBoolean("active", false) || xp > 0;
        JSONObject q = s.optJSONObject("quests");
        int qd = q != null ? q.optInt("done") : 0, qt = q != null ? q.optInt("total") : 0;
        String title, text;
        if (!active && streak > 0) {
            title = "🔥 連續 " + streak + " 天，今天還沒紀錄";
            text = "再做 1 件就保住，變成 " + (streak + 1) + " 天。喝一杯水也算。"
                    + (freezes > 0 ? "沒做的話會用掉 1 張保護卡（還有 " + freezes + " 張）。" : "");
        } else if (!active) {
            title = "今日回顧";
            text = s.optBoolean("comeback", false) ? "斷了沒關係：今天回來打卡，接下來 3 天 XP ×1.5。做 1 件就好。"
                    : "今天還沒有任何紀錄。花 1 分鐘補登，順便看看有沒有喝手搖、看電影要兌換。";
        } else {
            title = streak > 0 ? "🔥 連續 " + streak + " 天，今天保住了" : "今日回顧";
            text = "今天 +" + xp + " XP" + (qt > 0 ? "，每日任務 " + qd + " / " + qt + (qd < qt ? "（全清還能開寶箱）" : "") : "") + "。有漏記的事或要兌換的獎勵嗎？";
        }
        n.notify(N_REMIND, builder(c, CH_GOAL).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(openApp(c, "review")).build());
    }

    static void notifyCurfewSoon(Context c) {
        NotificationManager n = nm(c);
        if (n == null || !canNotify(c)) return;
        JSONObject cf = curfewCfg(c);
        n.notify(N_CURFEW, builder(c, CH_GOAL).setContentTitle("10 分鐘後開始宵禁 🌙")
                .setContentText(cf.optString("start", "01:00") + "–" + cf.optString("end", "07:00") + " 只能用白名單 App。守住一整晚會自動記錄。")
                .setContentIntent(openApp(c, null)).build());
    }

    /** 列出可以啟動的 App（給網頁的白名單 / 分心 App 選單）。 */
    static JSONArray listApps(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        Set<String> seen = new HashSet<>();
        JSONArray out = new JSONArray();
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || !seen.add(pkg)) continue;
            JSONObject o = new JSONObject();
            GoalStore.put(o, "pkg", pkg);
            GoalStore.put(o, "label", String.valueOf(ri.loadLabel(pm)));
            GoalStore.put(o, "system", systemAllowed(c, pkg));
            out.put(o);
        }
        return out;
    }

    static String defaultSms(Context c) {
        try { return Telephony.Sms.getDefaultSmsPackage(c); } catch (Exception e) { return null; }
    }
}
