package com.wayne.goal;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 鎖定畫面。三種模式：
 * focus  專注中（倒數、取消、緊急解鎖）
 * curfew 宵禁中（緊急解鎖）
 * gate   分心 App：呼吸 → 寫原因 → 選時間 → 放行（今天的次數用完可以用代幣加買，嚴格模式不行）
 * 返回鍵一律回主畫面，不會露出底下被擋的 App。
 */
public class LockActivity extends Activity {
    static final String FOCUS = "focus", CURFEW = "curfew", GATE = "gate";
    private static final int BG = 0xFF1B3A31, CARD = 0xFF24473D, BRASS = 0xFFD6A243, CHALK = 0xFFF3EFE6, DIM = 0xB3F3EFE6, RED = 0xFFE07A5F;
    private static final String[] REASONS = {"回訊息", "查資料", "放鬆一下", "無聊", "習慣性打開"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String mode = FOCUS, pkg = "", label = "";
    private LinearLayout root;
    private TextView countdown;
    private Runnable ticker;
    private ValueAnimator breathAnim;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goHome);
        }
        read(getIntent());
        build();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        read(intent);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!stillNeeded()) finish();
    }

    @Override
    protected void onDestroy() {
        stopTimers();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() { goHome(); }

    private void read(Intent i) {
        mode = i.getStringExtra("mode"); if (mode == null) mode = FOCUS;
        pkg = i.getStringExtra("pkg"); if (pkg == null) pkg = "";
        label = i.getStringExtra("label"); if (label == null) label = pkg;
    }

    private boolean stillNeeded() {
        long now = System.currentTimeMillis();
        GuardState.Mode m = GuardState.mode(this, now);
        if (FOCUS.equals(mode)) return m == GuardState.Mode.FOCUS;
        if (CURFEW.equals(mode)) return m == GuardState.Mode.CURFEW;
        return m == GuardState.Mode.NONE && !GuardState.gateOpen(this, now);
    }

    private void stopTimers() {
        if (ticker != null) handler.removeCallbacks(ticker);
        ticker = null;
        if (breathAnim != null) breathAnim.cancel();
        breathAnim = null;
        handler.removeCallbacksAndMessages(null);
    }

    // ---------- 共用的小元件 ----------
    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private GradientDrawable round(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill); g.setCornerRadius(dp(14));
        if (stroke != 0) g.setStroke(dp(1.5f), stroke);
        return g;
    }

    private Button button(String s, boolean primary, View.OnClickListener on) {
        Button bt = new Button(this);
        bt.setText(s); bt.setAllCaps(false); bt.setTextSize(16);
        bt.setTextColor(primary ? 0xFF1B2A24 : CHALK);
        bt.setBackground(round(primary ? BRASS : 0x22FFFFFF, primary ? 0 : 0x55F3EFE6));
        bt.setPadding(dp(16), dp(12), dp(16), dp(12));
        bt.setOnClickListener(on);
        return bt;
    }

    private void add(View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        root.addView(v, lp);
    }

    private void reset() {
        stopTimers();
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(64), dp(28), dp(40));
        sv.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);
    }

    private void build() {
        if (GATE.equals(mode)) buildGate(); else buildLock();
    }

    // ---------- 專注 / 宵禁 ----------
    private void buildLock() {
        reset();
        boolean focus = FOCUS.equals(mode);
        JSONObject s = GuardState.session(this);
        if (focus && s == null) { finish(); return; }
        add(text(focus ? s.optString("icon", "🎯") : "🌙", 64, CHALK, false), 0);
        add(text(focus ? "專注中：" + s.optString("name") : "宵禁中", 26, CHALK, true), 12);
        countdown = text("", 54, BRASS, true);
        countdown.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        add(countdown, 18);
        String sub;
        if (focus) sub = "「" + label + "」現在不能用。時間到會自動記錄 +" + s.optInt("xp") + " XP、+" + s.optInt("coins") + " 代幣。";
        else {
            JSONObject cf = GuardState.curfewCfg(this);
            sub = "現在是睡覺時間，只能用電話、鬧鐘和白名單 App。守住一整晚會自動記錄獎勵。\n" + cf.optString("end", "07:00") + " 解鎖。";
        }
        add(text(sub, 16, DIM, false), 10);
        add(button("回主畫面", true, v -> goHome()), 36);
        add(button("打開 Goal", false, v -> openGoal()), 12);
        if (focus && GuardState.canCancel(this)) {
            add(button("開始不到 2 分鐘：取消（不扣分）", false, v -> {
                if (GuardState.cancel(this)) { GoalWidgetProvider.updateAll(this); finish(); }
                else build();
            }), 12);
        }
        Button emergency = button("緊急解鎖…", false, v -> buildEmergency());
        emergency.setTextColor(RED);
        add(emergency, 28);
        startTicker(focus, s);
    }

    private void startTicker(boolean focus, JSONObject s) {
        ticker = new Runnable() {
            @Override public void run() {
                long now = System.currentTimeMillis(), end;
                if (focus) end = s.optLong("end");
                else { long[] w = GuardState.curfewWindowAt(LockActivity.this, now); end = w != null ? w[1] : now; }
                long left = Math.max(0, end - now);
                if (left == 0) { GuardState.settle(LockActivity.this); finish(); return; }
                long sec = left / 1000;
                countdown.setText(sec >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)
                        : String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60));
                handler.postDelayed(this, 1000);
            }
        };
        ticker.run();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void buildEmergency() {
        reset();
        boolean focus = FOCUS.equals(mode);
        JSONObject s = GuardState.session(this);
        int penalty = focus && s != null ? s.optInt("penalty") : GuardState.curfewCfg(this).optInt("penalty", 30);
        add(text("⚠️", 52, CHALK, false), 0);
        add(text("緊急解鎖", 26, CHALK, true), 10);
        String cost = focus
                ? "這次專注不給獎勵，扣 " + penalty + " 代幣，專注連勝歸零。\n這週每多解鎖一次，扣的代幣加倍。"
                : "今晚的宵禁不算守住，扣 " + penalty + " 代幣。\n解鎖後到 " + GuardState.curfewCfg(this).optString("end", "07:00") + " 都不會再鎖。";
        add(text(cost, 16, DIM, false), 12);
        final EditText reason = new EditText(this);
        reason.setHint("寫一句為什麼要解鎖（必填）");
        reason.setHintTextColor(0x88F3EFE6);
        reason.setTextColor(CHALK);
        reason.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        reason.setBackground(round(0x22000000, 0x55F3EFE6));
        reason.setPadding(dp(14), dp(12), dp(14), dp(12));
        add(reason, 24);
        final Button hold = button("按住 5 秒解鎖", false, null);
        hold.setTextColor(RED);
        final long[] downAt = {0};
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (downAt[0] == 0) return;
            long left = 5000 - (System.currentTimeMillis() - downAt[0]);
            if (left <= 0) {
                downAt[0] = 0;
                if (GuardState.abort(this, reason.getText().toString().trim())) { GoalWidgetProvider.updateAll(this); finish(); }
                else finish();
                return;
            }
            hold.setText(String.format(Locale.US, "繼續按住… %.1f", left / 1000f));
            handler.postDelayed(tick[0], 100);
        };
        hold.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                if (reason.getText().toString().trim().length() < 2) { reason.setError("先寫一句原因"); return true; }
                downAt[0] = System.currentTimeMillis();
                handler.post(tick[0]);
                return true;
            }
            if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                downAt[0] = 0;
                handler.removeCallbacks(tick[0]);
                hold.setText("按住 5 秒解鎖");
                return true;
            }
            return false;
        });
        add(hold, 16);
        add(button("算了，繼續撐", true, v -> build()), 12);
    }

    // ---------- 分心 App ----------
    private void buildGate() {
        reset();
        JSONObject g = GuardState.gateCfg(this);
        int left = GuardState.gateLeft(this);
        boolean strict = g.optBoolean("strict", false);
        int cost = g.optInt("extraCost", 30), coins = GuardState.coins(this);
        add(text("🫁", 56, CHALK, false), 0);
        add(text("「" + label + "」上鎖中", 24, CHALK, true), 10);
        if (left > 0) {
            add(text("今天還可以放行 " + left + " 次。先深呼吸，再決定要不要打開。", 16, DIM, false), 10);
            add(button("深呼吸 " + g.optInt("breath", 10) + " 秒，然後解鎖", true, v -> buildBreath(0)), 32);
        } else if (strict) {
            add(text("今天的放行次數用完了。嚴格模式下，明天才能再打開。", 16, DIM, false), 10);
        } else {
            add(text("今天的免費放行用完了。", 16, DIM, false), 10);
            Button buy = button("用 " + cost + " 代幣加買一次（你有 " + coins + "）", true, v -> buildBreath(cost));
            buy.setEnabled(coins >= cost);
            if (coins < cost) buy.setAlpha(0.5f);
            add(buy, 32);
        }
        add(button("算了，不開了（+3 代幣）", false, v -> { GuardState.skipGate(this, label); goHome(); }), 12);
        add(button("回主畫面", false, v -> goHome()), 12);
    }

    private void buildBreath(int paid) {
        reset();
        int secs = Math.max(3, Math.min(60, GuardState.gateCfg(this).optInt("breath", 10)));
        add(text("跟著圓圈呼吸", 22, CHALK, true), 0);
        final View circle = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL); d.setColor(0x33D6A243); d.setStroke(dp(3), BRASS);
        circle.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(200), dp(200));
        lp.topMargin = dp(40); lp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(circle, lp);
        final TextView hint = text("吸氣…", 20, BRASS, true);
        add(hint, 36);
        final TextView left = text("", 15, DIM, false);
        add(left, 8);
        breathAnim = ValueAnimator.ofFloat(0.55f, 1f);
        breathAnim.setDuration(4000);
        breathAnim.setRepeatMode(ValueAnimator.REVERSE);
        breathAnim.setRepeatCount(ValueAnimator.INFINITE);
        breathAnim.addUpdateListener(a -> { float f = (float) a.getAnimatedValue(); circle.setScaleX(f); circle.setScaleY(f); });
        breathAnim.start();
        final long end = System.currentTimeMillis() + secs * 1000L;
        ticker = new Runnable() {
            @Override public void run() {
                long ms = end - System.currentTimeMillis();
                if (ms <= 0) { buildReason(paid); return; }
                hint.setText((ms / 4000) % 2 == ((secs * 1000L) / 4000) % 2 ? "吸氣…" : "吐氣…");
                left.setText("還有 " + (ms / 1000 + 1) + " 秒");
                handler.postDelayed(this, 250);
            }
        };
        ticker.run();
    }

    private void buildReason(int paid) {
        reset();
        add(text("為什麼要打開「" + label + "」？", 22, CHALK, true), 0);
        add(text("說出原因，比較不會不知不覺滑太久。", 15, DIM, false), 8);
        final EditText other = new EditText(this);
        other.setHint("或自己寫…");
        other.setHintTextColor(0x88F3EFE6); other.setTextColor(CHALK);
        other.setBackground(round(0x22000000, 0x55F3EFE6));
        other.setPadding(dp(14), dp(12), dp(14), dp(12));
        for (String r : REASONS) add(button(r, false, v -> buildMinutes(paid, r)), 10);
        add(other, 16);
        add(button("下一步", true, v -> {
            String r = other.getText().toString().trim();
            if (r.length() < 2) { other.setError("選一個原因，或寫兩個字以上"); return; }
            buildMinutes(paid, r);
        }), 12);
        add(button("算了，不開了（+3 代幣）", false, v -> { GuardState.skipGate(this, label); goHome(); }), 20);
    }

    private void buildMinutes(int paid, String reason) {
        reset();
        int max = Math.max(1, Math.min(60, GuardState.gateCfg(this).optInt("minutes", 10)));
        add(text("要用多久？", 22, CHALK, true), 0);
        add(text("時間到會自動鎖回去，就算滑到一半也一樣。", 15, DIM, false), 8);
        int[] opts = {3, 5, 10, 15, 20, 30};
        boolean any = false;
        for (int m : opts) {
            if (m > max) continue;
            any = true;
            add(button(m + " 分鐘", m == Math.min(5, max), v -> { GuardState.openGate(this, m, reason, label, paid); finish(); }), 10);
        }
        if (!any) add(button(max + " 分鐘", true, v -> { GuardState.openGate(this, max, reason, label, paid); finish(); }), 10);
    }

    // ---------- 導覽 ----------
    private void goHome() {
        Intent h = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(h); } catch (Exception ignored) { }
        finish();
    }

    private void openGoal() {
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("goal_open", "focus"));
        finish();
    }
}
