package com.wayne.goal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 鬧鐘與開機：專注結束入帳、晚上提醒、宵禁前提醒、宵禁結束記錄；開機或 App 更新後重新排程。 */
public class GuardReceiver extends BroadcastReceiver {
    static final String FOCUS_END = "com.wayne.goal.FOCUS_END";
    static final String REMIND = "com.wayne.goal.REMIND";
    static final String CURFEW_WARN = "com.wayne.goal.CURFEW_WARN";
    static final String CURFEW_END = "com.wayne.goal.CURFEW_END";

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (a == null) return;
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                if (REMIND.equals(a)) GuardState.notifyReminder(c);
                else if (CURFEW_WARN.equals(a)) GuardState.notifyCurfewSoon(c);
                GuardState.settle(c);          // 專注時間到、宵禁結束都在這裡入帳
                GuardState.scheduleAll(c);     // 排下一次
                GoalWidgetProvider.updateAll(c);
            } finally {
                pr.finish();
            }
        }).start();
    }
}
