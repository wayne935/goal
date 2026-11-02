package com.wayne.goal;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.health.connect.AggregateRecordsRequest;
import android.health.connect.AggregateRecordsResponse;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.ReadRecordsRequestUsingFilters;
import android.health.connect.ReadRecordsResponse;
import android.health.connect.TimeInstantRangeFilter;
import android.health.connect.datatypes.ExerciseSessionRecord;
import android.health.connect.datatypes.SleepSessionRecord;
import android.health.connect.datatypes.StepsRecord;
import android.os.Build;
import android.os.OutcomeReceiver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 讀 Health Connect（Android 14 以上內建在系統裡，不需要另外裝函式庫）：每日步數、運動、睡眠。
 * 只讀不寫，而且只在打開 App 時讀（不需要背景讀取權限）。轉成 Goal 事件的規則在網頁。
 */
@TargetApi(34)
final class HealthBridge {
    static final String[] PERMS = {
            "android.permission.health.READ_STEPS",
            "android.permission.health.READ_EXERCISE",
            "android.permission.health.READ_SLEEP"};

    private HealthBridge() {}

    static boolean available(Context c) {
        return Build.VERSION.SDK_INT >= 34 && c.getSystemService(HealthConnectManager.class) != null;
    }

    static boolean granted(Context c, String p) {
        return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    static JSONObject status(Context c) {
        JSONObject r = new JSONObject();
        boolean ok = available(c);
        GoalStore.put(r, "available", ok);
        if (ok) {
            GoalStore.put(r, "steps", granted(c, PERMS[0]));
            GoalStore.put(r, "exercise", granted(c, PERMS[1]));
            GoalStore.put(r, "sleep", granted(c, PERMS[2]));
        }
        return r;
    }

    /** 讀最近 days 天。在背景執行緒呼叫（會等待結果）。 */
    static JSONObject read(Context c, int days) throws Exception {
        if (!available(c)) throw new Exception("這支手機沒有 Health Connect（需要 Android 14 以上）");
        HealthConnectManager hcm = c.getSystemService(HealthConnectManager.class);
        java.util.concurrent.ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        Instant from = today.minusDays(Math.max(1, Math.min(14, days)) - 1).atStartOfDay(zone).toInstant(), to = Instant.now();
        JSONObject out = new JSONObject();

        JSONArray steps = new JSONArray();
        if (granted(c, PERMS[0])) {
            for (LocalDate d = today.minusDays(Math.max(1, Math.min(14, days)) - 1); !d.isAfter(today); d = d.plusDays(1)) {
                Instant a = d.atStartOfDay(zone).toInstant(), b = d.plusDays(1).atStartOfDay(zone).toInstant();
                if (b.isAfter(to)) b = to;
                AggregateRecordsRequest<Long> req = new AggregateRecordsRequest.Builder<Long>(
                        new TimeInstantRangeFilter.Builder().setStartTime(a).setEndTime(b).build())
                        .addAggregationType(StepsRecord.STEPS_COUNT_TOTAL).build();
                final Long[] res = {null};
                CountDownLatch l = new CountDownLatch(1);
                hcm.aggregate(req, ex, new OutcomeReceiver<AggregateRecordsResponse<Long>, HealthConnectException>() {
                    @Override public void onResult(AggregateRecordsResponse<Long> r) { res[0] = r.get(StepsRecord.STEPS_COUNT_TOTAL); l.countDown(); }
                    @Override public void onError(HealthConnectException e) { l.countDown(); }
                });
                l.await(10, TimeUnit.SECONDS);
                JSONObject o = new JSONObject();
                GoalStore.put(o, "day", d.toString());
                GoalStore.put(o, "count", res[0] == null ? 0 : res[0]);
                steps.put(o);
            }
        }
        GoalStore.put(out, "steps", steps);

        JSONArray exercise = new JSONArray();
        if (granted(c, PERMS[1])) {
            for (ExerciseSessionRecord r : readAll(hcm, ex, ExerciseSessionRecord.class, from, to)) {
                JSONObject o = new JSONObject();
                GoalStore.put(o, "id", r.getMetadata().getId());
                GoalStore.put(o, "start", r.getStartTime().toEpochMilli());
                GoalStore.put(o, "end", r.getEndTime().toEpochMilli());
                GoalStore.put(o, "type", r.getExerciseType());
                GoalStore.put(o, "title", r.getTitle() == null ? "" : r.getTitle().toString());
                exercise.put(o);
            }
        }
        GoalStore.put(out, "exercise", exercise);

        JSONArray sleep = new JSONArray();
        if (granted(c, PERMS[2])) {
            for (SleepSessionRecord r : readAll(hcm, ex, SleepSessionRecord.class, from.minusSeconds(12 * 3600), to)) {
                JSONObject o = new JSONObject();
                GoalStore.put(o, "id", r.getMetadata().getId());
                GoalStore.put(o, "start", r.getStartTime().toEpochMilli());
                GoalStore.put(o, "end", r.getEndTime().toEpochMilli());
                sleep.put(o);
            }
        }
        GoalStore.put(out, "sleep", sleep);
        return out;
        } finally {
            ex.shutdown();
        }
    }

    private static <T extends android.health.connect.datatypes.Record> java.util.List<T> readAll(
            HealthConnectManager hcm, Executor ex, Class<T> cls, Instant from, Instant to) throws InterruptedException {
        ReadRecordsRequestUsingFilters<T> req = new ReadRecordsRequestUsingFilters.Builder<>(cls)
                .setTimeRangeFilter(new TimeInstantRangeFilter.Builder().setStartTime(from).setEndTime(to).build())
                .setPageSize(200).build();
        final java.util.List<T> list = new java.util.ArrayList<>();
        CountDownLatch l = new CountDownLatch(1);
        hcm.readRecords(req, ex, new OutcomeReceiver<ReadRecordsResponse<T>, HealthConnectException>() {
            @Override public void onResult(ReadRecordsResponse<T> r) { list.addAll(r.getRecords()); l.countDown(); }
            @Override public void onError(HealthConnectException e) { l.countDown(); }
        });
        l.await(10, TimeUnit.SECONDS);
        return list;
    }
}
