package com.wayne.goal;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** 桌面小工具：等級、連續天數、代幣、今日 XP 與任務，加上三個一鍵打卡按鈕。 */
public class GoalWidgetProvider extends AppWidgetProvider {
    static final String ACTION_LOG = "com.wayne.goal.LOG";
    private static final int[] BUTTONS = { R.id.w_btn1, R.id.w_btn2, R.id.w_btn3 };

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        RemoteViews v = build(ctx);
        for (int id : ids) mgr.updateAppWidget(id, v);
        final Context app = ctx.getApplicationContext();
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try { GoalStore.flushQueue(app); } finally { pr.finish(); }
        }).start();
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        if (!ACTION_LOG.equals(intent.getAction())) return;
        String habit = intent.getStringExtra("habit");
        if (habit == null || habit.isEmpty()) return;
        String name = GoalStore.logHabit(ctx, habit);
        updateAll(ctx);
        Toast.makeText(ctx, "已記錄：" + name, Toast.LENGTH_SHORT).show();
        final Context app = ctx.getApplicationContext();
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try { GoalStore.flushQueue(app); } finally { pr.finish(); }
        }).start();
    }

    static void updateAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, GoalWidgetProvider.class));
        if (ids == null || ids.length == 0) return;
        RemoteViews v = build(ctx);
        for (int id : ids) mgr.updateAppWidget(id, v);
    }

    static RemoteViews build(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.goal_widget);
        JSONObject s = GoalStore.currentSummary(ctx);

        v.setTextViewText(R.id.w_level, "Lv " + s.optInt("level", 1));
        int need = Math.max(1, s.optInt("need", 120));
        v.setProgressBar(R.id.w_progress, need, Math.max(0, Math.min(need, s.optInt("into", 0))), false);
        v.setTextViewText(R.id.w_streak, "🔥 " + s.optInt("streak", 0));
        v.setTextViewText(R.id.w_coins, "🪙 " + s.optInt("coins", 0));

        String line;
        if (s.optBoolean("empty", false)) {
            line = "點這裡打開 Goal 同步資料";
        } else {
            JSONObject q = s.optJSONObject("quests");
            line = "今天 +" + s.optInt("todayXp", 0) + " XP";
            if (q != null && q.optInt("total", 0) > 0) line += "　任務 " + q.optInt("done", 0) + "/" + q.optInt("total", 0);
        }
        v.setTextViewText(R.id.w_today, line);

        JSONArray b = s.optJSONArray("buttons");
        for (int i = 0; i < BUTTONS.length; i++) {
            JSONObject bt = b != null ? b.optJSONObject(i) : null;
            if (bt == null) { v.setViewVisibility(BUTTONS[i], View.GONE); continue; }
            v.setViewVisibility(BUTTONS[i], View.VISIBLE);
            String label = bt.optString("icon") + "  " + bt.optInt("count", 0) + "/" + bt.optInt("max", 1) + "\n" + bt.optString("name");
            v.setTextViewText(BUTTONS[i], label);
            v.setContentDescription(BUTTONS[i], "記錄" + bt.optString("name"));
            Intent it = new Intent(ctx, GoalWidgetProvider.class)
                    .setAction(ACTION_LOG)
                    .putExtra("habit", bt.optString("habit"));
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 100 + i, it,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            v.setOnClickPendingIntent(BUTTONS[i], pi);
        }

        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent po = PendingIntent.getActivity(ctx, 1, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.w_header, po);
        return v;
    }
}
