package com.wayne.goal;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // 重新建置安裝後，若 App 內建的網頁比下載的新，就改用內建版本
        GoalUpdater.reconcile(this);
        registerPlugin(GoalWidgetPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
