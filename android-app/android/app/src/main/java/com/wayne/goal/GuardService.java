package com.wayne.goal;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * 守護服務（無障礙服務）：只看「現在前景是哪個 App」，不讀畫面內容。
 * 專注、宵禁時打開白名單以外的 App，或打開還沒放行的分心 App，就蓋上 LockActivity。
 */
public class GuardService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastPkg = null;
    private long lastShown = 0;
    private String lastShownPkg = null;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            GuardState.settle(GuardService.this);
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isInteractive()) evaluate(lastPkg, true);   // 螢幕關著時不用蓋鎖定畫面
            handler.postDelayed(this, 15_000L);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        GuardState.markUp(this);
        GuardState.resetCaches();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 15_000L);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = event.getPackageName();
        if (p == null) return;
        String pkg = p.toString();
        lastPkg = pkg;
        evaluate(pkg, false);
    }

    private void evaluate(String pkg, boolean fromTick) {
        if (pkg == null || pkg.equals(getPackageName())) return;
        long now = System.currentTimeMillis();
        GuardState.Mode m = GuardState.mode(this, now);
        String why = null;
        if (m != GuardState.Mode.NONE) {
            if (!GuardState.allowed(this, pkg, m)) why = m == GuardState.Mode.FOCUS ? LockActivity.FOCUS : LockActivity.CURFEW;
        } else if (GuardState.isDistracting(this, pkg) && !GuardState.gateOpen(this, now) && !GuardState.systemAllowed(this, pkg)) {
            why = LockActivity.GATE;
        }
        if (why == null) return;
        // 同一個 App 連續觸發時不要一直重開鎖定畫面
        if (!fromTick && pkg.equals(lastShownPkg) && now - lastShown < 800) return;
        lastShown = now; lastShownPkg = pkg;
        String label = pkg;
        try { label = String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))); }
        catch (Exception ignored) { }
        Intent i = new Intent(this, LockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("mode", why).putExtra("pkg", pkg).putExtra("label", label);
        try { startActivity(i); } catch (Exception e) { performGlobalAction(GLOBAL_ACTION_HOME); }
    }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        GuardState.markDown(this);
        handler.removeCallbacks(tick);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        GuardState.markDown(this);
        handler.removeCallbacks(tick);
        super.onDestroy();
    }
}
