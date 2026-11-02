package com.wayne.goal;

import android.content.Intent;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // 重新建置安裝後，若 App 內建的網頁比下載的新，就改用內建版本
        GoalUpdater.reconcile(this);
        registerPlugin(GoalWidgetPlugin.class);
        super.onCreate(savedInstanceState);
        rememberLaunch(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        rememberLaunch(intent);
    }

    /** 從通知或鎖定畫面打開時要跳到哪裡（review / focus），網頁用 consumeLaunch 取走。 */
    private void rememberLaunch(Intent i) {
        String open = i != null ? i.getStringExtra("goal_open") : null;
        if (open != null) GuardState.prefs(this).edit().putString("launch", open).apply();
    }
}
