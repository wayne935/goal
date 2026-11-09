package com.wayne.goal;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 守護服務（無障礙服務）：只看「現在前景是哪個 App」，不讀畫面內容。
 * 專注、宵禁時打開白名單以外的 App，或打開還沒放行的分心 App，就蓋上 LockActivity。
 *
 * 三層保險（build 19）：
 * 1. 視窗切換事件：打開 App 的當下就判斷。
 * 2. 定時檢查：放行到期、專注結束的那一刻準時檢查一次，平常每 15 秒一次，螢幕打開時也檢查；
 *    檢查用的「前景 App」只認真正的 Activity，不會被鍵盤、通知欄、對話框蓋掉。
 * 3. 鎖定畫面 1.5 秒內沒出現（被系統擋掉）就直接回主畫面，並發通知說明。
 */
public class GuardService extends AccessibilityService {
    private static final long TICK_MS = 15_000L, VERIFY_MS = 1_500L;
    private static volatile GuardService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Boolean> activityCache = new HashMap<>();
    private String fgPkg = null;          // 最近一次真正切到前景的 App（只認 Activity）
    private long lastShown = 0;
    private String lastShownPkg = null;
    private BroadcastReceiver screenRx;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            GuardState.settle(GuardService.this);
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isInteractive()) evaluate(fgPkg, true);   // 螢幕關著時不用蓋鎖定畫面
            scheduleNext();
        }
    };

    /** 放行、開始或結束專注之後呼叫：馬上重新排下一次檢查（排到到期的那一刻）。 */
    static void poke() {
        GuardService s = instance;
        if (s != null) s.handler.post(s::scheduleNext);
    }

    private void scheduleNext() {
        handler.removeCallbacks(tick);
        long now = System.currentTimeMillis(), d = TICK_MS;
        long gu = GuardState.gateUntil(this);
        if (gu > now) d = Math.min(d, gu - now + 300);
        JSONObject s = GuardState.session(this);
        if (s != null && s.optLong("end") > now) d = Math.min(d, s.optLong("end") - now + 300);
        handler.postDelayed(tick, Math.max(300, d));
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        GuardState.markUp(this);
        GuardState.resetCaches();
        screenRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { handler.removeCallbacks(tick); handler.postDelayed(tick, 500); }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenRx, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenRx, f);
        } catch (Exception ignored) { screenRx = null; }
        scheduleNext();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = event.getPackageName();
        if (p == null) return;
        String pkg = p.toString();
        CharSequence cls = event.getClassName();
        if (isForegroundApp(pkg, cls == null ? null : cls.toString())) fgPkg = pkg;
        evaluate(pkg, false);
    }

    /** 這個事件是不是「換了一個 App 到前景」：是某個 App 的 Activity 才算（鍵盤、通知欄、對話框、Toast 都不算）。 */
    private boolean isForegroundApp(String pkg, String cls) {
        if (pkg.equals(getPackageName()) || pkg.equals(GuardState.homePkg(this))) return true;   // 回到主畫面也算換了 App
        if ("android".equals(pkg) || "com.android.systemui".equals(pkg) || GuardState.isIme(this, pkg)) return false;
        if (cls == null || cls.isEmpty()) return false;
        String key = pkg + "/" + cls;
        Boolean hit = activityCache.get(key);
        if (hit == null) {
            PackageManager pm = getPackageManager();
            try { pm.getActivityInfo(new ComponentName(pkg, cls), 0); hit = true; }
            catch (PackageManager.NameNotFoundException e) {
                // 看得到這個 App、但不是它的 Activity（對話框、彈出視窗）→ 不算；整個 App 都看不到（沒有桌面圖示的系統 App）→ 當作換到它
                try { pm.getApplicationInfo(pkg, 0); hit = false; } catch (Exception e2) { hit = true; }
            }
            catch (Exception e) { hit = false; }
            if (activityCache.size() > 500) activityCache.clear();
            activityCache.put(key, hit);
        }
        return hit;
    }

    /** 現在這個 App 該不該擋；回傳 LockActivity 的模式，不用擋就回傳 null。 */
    private String blockReason(String pkg, long now) {
        if (pkg == null || pkg.equals(getPackageName())) return null;
        GuardState.Mode m = GuardState.mode(this, now);
        if (m != GuardState.Mode.NONE) return GuardState.allowed(this, pkg, m) ? null : m == GuardState.Mode.FOCUS ? LockActivity.FOCUS : LockActivity.CURFEW;
        if (GuardState.isDistracting(this, pkg) && !GuardState.gateOpen(this, now) && !GuardState.systemAllowed(this, pkg)) return LockActivity.GATE;
        return null;
    }

    private void evaluate(String pkg, boolean fromTick) {
        long now = System.currentTimeMillis();
        String why = blockReason(pkg, now);
        if (why == null) return;
        // 鎖定畫面已經蓋著就不用重開；同一個 App 連續觸發時也不要一直重開
        if (LockActivity.shown) return;
        if (!fromTick && pkg.equals(lastShownPkg) && now - lastShown < 800) return;
        lastShown = now; lastShownPkg = pkg;
        String label = pkg;
        try { label = String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))); }
        catch (Exception ignored) { }
        long gu = GuardState.gateUntil(this);
        boolean expired = LockActivity.GATE.equals(why) && gu > 0 && now - gu < 120_000L;   // 剛剛放行到期
        Intent i = new Intent(this, LockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("mode", why).putExtra("pkg", pkg).putExtra("label", label).putExtra("expired", expired);
        try { startActivity(i); } catch (Exception e) { fallbackHome(pkg, label, why); return; }
        final String fp = pkg, fl = label, fw = why;
        handler.postDelayed(() -> {   // 第三層：鎖定畫面沒出現就回主畫面
            if (LockActivity.shown || !fp.equals(fgPkg) || blockReason(fp, System.currentTimeMillis()) == null) return;
            fallbackHome(fp, fl, fw);
        }, VERIFY_MS);
    }

    private void fallbackHome(String pkg, String label, String why) {
        performGlobalAction(GLOBAL_ACTION_HOME);
        String text = LockActivity.GATE.equals(why) ? "「" + label + "」的放行時間到了，先回到主畫面。要再用的話打開它，會出現放行畫面。"
                : LockActivity.FOCUS.equals(why) ? "專注中，「" + label + "」現在不能用。" : "宵禁中，「" + label + "」現在不能用。";
        GuardState.notifyDone(this, "「" + label + "」先鎖起來了", text);
    }

    @Override
    public void onInterrupt() { }

    private void stop() {
        GuardState.markDown(this);
        handler.removeCallbacksAndMessages(null);
        if (screenRx != null) { try { unregisterReceiver(screenRx); } catch (Exception ignored) { } screenRx = null; }
        if (instance == this) instance = null;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stop();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }
}
