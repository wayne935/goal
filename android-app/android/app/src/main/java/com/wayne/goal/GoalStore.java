package com.wayne.goal;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Goal 的原生端資料：登入狀態、widget 顯示資料、widget 打卡佇列，以及上傳到 Supabase。
 * 登入狀態只在這裡更新（網頁與 widget 都透過這裡換新 token），避免兩邊各自換 token 互相作廢。
 */
final class GoalStore {
    static final String SB_URL = "https://cpfokqjomhznacezjpcx.supabase.co";
    static final String SB_KEY = "sb_publishable_GJQThVho5E7QeTLoqG-6qQ_zBsGd1_z";
    private static final String PREFS = "goal_widget";

    private GoalStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (JSONException ignored) { }
    }

    private static JSONObject parse(String s) {
        if (s == null) return null;
        try { return new JSONObject(s); } catch (JSONException e) { return null; }
    }

    // ---------- session ----------
    static synchronized JSONObject getSession(Context c) {
        return parse(prefs(c).getString("session", null));
    }

    static synchronized void setSession(Context c, JSONObject s) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (s == null) e.remove("session"); else e.putString("session", s.toString());
        e.apply();
    }

    /** 回傳有效的登入狀態；快過期時換新 token。登入已失效時清掉並丟出例外。 */
    static synchronized JSONObject refresh(Context c, boolean force) throws Exception {
        JSONObject s = getSession(c);
        if (s == null) throw new Exception("not logged in");
        long now = System.currentTimeMillis() / 1000;
        if (!force && s.optLong("expires_at", 0) - 60 > now) return s;
        JSONObject body = new JSONObject();
        put(body, "refresh_token", s.optString("refresh_token"));
        Http r = http("POST", SB_URL + "/auth/v1/token?grant_type=refresh_token", null, body.toString(), null);
        if (r.code == 400 || r.code == 401) { setSession(c, null); throw new Exception("session expired"); }
        if (r.code / 100 != 2) throw new IOException("refresh failed " + r.code);
        JSONObject j = new JSONObject(r.body);
        JSONObject user = j.optJSONObject("user");
        JSONObject ns = new JSONObject();
        put(ns, "access_token", j.getString("access_token"));
        put(ns, "refresh_token", j.getString("refresh_token"));
        put(ns, "expires_at", j.has("expires_at") ? j.getLong("expires_at") : now + j.optLong("expires_in", 3600));
        put(ns, "email", user != null ? user.optString("email", s.optString("email")) : s.optString("email"));
        put(ns, "uid", user != null ? user.optString("id", s.optString("uid")) : s.optString("uid"));
        setSession(c, ns);
        return ns;
    }

    // ---------- widget summary ----------
    static synchronized JSONObject getSummary(Context c) {
        return parse(prefs(c).getString("summary", null));
    }

    static synchronized void setSummary(Context c, JSONObject s) {
        prefs(c).edit().putString("summary", s.toString()).apply();
    }

    static JSONObject defaultSummary() {
        JSONObject s = new JSONObject();
        put(s, "empty", true);
        put(s, "dayStartHour", 4);
        put(s, "day", "");
        put(s, "level", 1);
        put(s, "into", 0);
        put(s, "need", 120);
        JSONArray b = new JSONArray();
        b.put(button("water", "💧", "喝一杯水", 8, 2, 1));
        b.put(button("exercise", "🏃", "運動", 2, 15, 10));
        b.put(button("paper", "📄", "讀一篇論文", 3, 20, 12));
        put(s, "buttons", b);
        return s;
    }

    private static JSONObject button(String habit, String icon, String name, int max, int xp, int coins) {
        JSONObject o = new JSONObject();
        put(o, "habit", habit); put(o, "icon", icon); put(o, "name", name);
        put(o, "count", 0); put(o, "max", max); put(o, "xp", xp); put(o, "coins", coins);
        return o;
    }

    /** 換日（依 dayStartHour）時把今日數字歸零，其他照舊。 */
    static synchronized JSONObject currentSummary(Context c) {
        JSONObject s = getSummary(c);
        if (s == null) s = defaultSummary();
        String today = dayKey(s.optInt("dayStartHour", 4));
        if (!today.equals(s.optString("day"))) {
            put(s, "day", today);
            put(s, "todayXp", 0);
            JSONObject q = s.optJSONObject("quests");
            if (q != null) put(q, "done", 0);
            JSONArray b = s.optJSONArray("buttons");
            if (b != null) for (int i = 0; i < b.length(); i++) {
                JSONObject bt = b.optJSONObject(i);
                if (bt != null) put(bt, "count", 0);
            }
        }
        return s;
    }

    // ---------- widget logging ----------
    /** 記錄一次習慣：放進佇列並更新 widget 顯示。回傳習慣名稱。 */
    static synchronized String logHabit(Context c, String habit) {
        JSONObject ev = new JSONObject();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        put(ev, "id", "w-" + stamp + "-" + habit + "-" + Integer.toHexString(new Random().nextInt(0x10000)));
        put(ev, "type", "habit");
        put(ev, "habit", habit);
        put(ev, "ts", nowIso());
        put(ev, "note", "");
        put(ev, "source", "widget");
        JSONArray q = getQueue(c);
        q.put(ev);
        setQueue(c, q);

        JSONObject s = currentSummary(c);
        String name = habit;
        JSONArray b = s.optJSONArray("buttons");
        if (b != null) for (int i = 0; i < b.length(); i++) {
            JSONObject bt = b.optJSONObject(i);
            if (bt == null || !habit.equals(bt.optString("habit"))) continue;
            name = bt.optString("name", habit);
            int count = bt.optInt("count") + 1;
            put(bt, "count", count);
            if (count <= bt.optInt("max", 1)) {
                put(s, "todayXp", s.optInt("todayXp") + bt.optInt("xp"));
                put(s, "coins", s.optInt("coins") + bt.optInt("coins"));
            }
        }
        setSummary(c, s);
        return name;
    }

    static synchronized JSONArray getQueue(Context c) {
        try { return new JSONArray(prefs(c).getString("queue", "[]")); } catch (JSONException e) { return new JSONArray(); }
    }

    private static synchronized void setQueue(Context c, JSONArray q) {
        prefs(c).edit().putString("queue", q.toString()).apply();
    }

    static synchronized void removeFromQueue(Context c, Set<String> ids) {
        JSONArray q = getQueue(c), out = new JSONArray();
        for (int i = 0; i < q.length(); i++) {
            JSONObject e = q.optJSONObject(i);
            if (e != null && !ids.contains(e.optString("id"))) out.put(e);
        }
        setQueue(c, out);
    }

    /** 把佇列裡的打卡上傳到雲端；沒登入或沒網路就留著，等下次或打開 App 時處理。 */
    static void flushQueue(Context c) {
        JSONArray q = getQueue(c);
        if (q.length() == 0) return;
        try {
            JSONObject s = refresh(c, false);
            JSONArray rows = new JSONArray();
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < q.length(); i++) {
                JSONObject ev = q.optJSONObject(i);
                if (ev == null) continue;
                JSONObject row = new JSONObject();
                put(row, "id", ev.optString("id"));
                put(row, "ts", ev.optString("ts"));
                put(row, "data", ev);
                put(row, "deleted", false);
                rows.put(row);
                ids.add(ev.optString("id"));
            }
            String url = SB_URL + "/rest/v1/goal_events?on_conflict=user_id,id";
            String prefer = "resolution=merge-duplicates,return=minimal";
            Http r = http("POST", url, s.getString("access_token"), rows.toString(), prefer);
            if (r.code == 401) {
                s = refresh(c, true);
                r = http("POST", url, s.getString("access_token"), rows.toString(), prefer);
            }
            if (r.code / 100 == 2) removeFromQueue(c, ids);
        } catch (Exception ignored) {
            // 留在佇列，下次再試
        }
    }

    // ---------- helpers ----------
    static String dayKey(int startHour) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR_OF_DAY, -startHour);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    static String nowIso() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date());
    }

    static final class Http {
        int code;
        String body;
    }

    static Http http(String method, String url, String bearer, String body, String prefer) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setRequestMethod(method);
            con.setConnectTimeout(8000);
            con.setReadTimeout(8000);
            con.setRequestProperty("apikey", SB_KEY);
            con.setRequestProperty("Content-Type", "application/json");
            if (bearer != null) con.setRequestProperty("Authorization", "Bearer " + bearer);
            if (prefer != null) con.setRequestProperty("Prefer", prefer);
            if (body != null) {
                con.setDoOutput(true);
                try (OutputStream os = con.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            Http r = new Http();
            r.code = con.getResponseCode();
            InputStream is = r.code >= 400 ? con.getErrorStream() : con.getInputStream();
            r.body = is == null ? "" : readAll(is);
            return r;
        } finally {
            con.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        try (InputStream in = is; ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
