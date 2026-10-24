package com.hozinking.bsmod;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * Auto-inject: polling tiap ~2 detik `pidof <package game>`.
 * - PID baru ketemu & belum di-inject -> inject otomatis -> overlay logo muncul.
 * - Game ditutup (pidof kosong) -> reset, overlay disembunyikan.
 *
 * Foreground service + notifikasi persisten agar tidak di-kill sistem.
 * Toggle "Auto-inject" di MainActivity (default ON).
 */
public class WatchService extends Service {

    private static final String TAG = "BsMod";
    private static final String CH_ID = "bsmod_watch";
    private static final long POLL_MS = 2000;
    private static final String DEFAULT_PKG = "net.froemling.bombsquad";

    private Handler handler;
    private Runnable poller;
    private boolean injecting = false;
    /** Hitungan poll beruntun saat game tidak jalan — cleanup jejak .so
     *  hanya setelah 2x poll mati (anti-blip pidof & anti-balapan). */
    private int deadTicks = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        startFg();
        handler = new Handler(Looper.getMainLooper());
        poller = new Runnable() {
            @Override public void run() {
                new Thread(() -> tick()).start();
                handler.postDelayed(this, POLL_MS);
            }
        };
        handler.post(poller);
        Log.i(TAG, "WatchService jalan (auto-inject aktif)");
    }

    private void startFg() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(
                    CH_ID, "BS Mod Auto-inject", NotificationManager.IMPORTANCE_MIN));
        }
        Notification.Builder nb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        Notification n = nb.setContentTitle("Hozin BS Mod aktif")
                .setContentText("Auto-inject berjalan")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .build();
        startForeground(2, n);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(ModInjector.PREFS, Context.MODE_PRIVATE);
    }

    private void tick() {
        if (injecting) return;
        String pkg = prefs().getString("package", DEFAULT_PKG);
        int pid = RootUtil.pidOf(pkg);
        int lastPid = prefs().getInt("last_pid", -1);

        if (pid <= 0) {
            if (lastPid != -1) {
                prefs().edit().putInt("last_pid", -1)
                        .putLong("inject_attempt_ms", 0).apply();
                Log.i(TAG, "Game ditutup, overlay disembunyikan");
                stopService(new Intent(this, OverlayService.class));
            }
            // Game mati 2x poll beruntun -> hapus jejak .so staging.
            // (Bukan langsung: anti-blip pidof & anti-balapan dengan inject.)
            if (++deadTicks == 2) {
                cleanupStagingIfGameDead(pkg);
            }
            return;
        }
        deadTicks = 0;
        if (pid == lastPid) return; // sudah ditangani

        // PID baru -> logo floating DIMUNCULKAN PAKSA segera (apapun hasil
        // inject nanti), lalu inject otomatis di background.
        injecting = true;
        prefs().edit().putInt("last_pid", pid).apply();
        Log.i(TAG, "Game dibuka (pid " + pid + ") — logo dimunculkan paksa + auto-inject...");
        showOverlay();
        new Thread(() -> {
            try {
                ModInjector.InjectResult r = ModInjector.inject(
                        WatchService.this, pkg,
                        s -> Log.i(TAG, "[auto] " + s), 10);
                if (r.statusJson != null && !r.statusJson.isEmpty()
                        && r.statusJson.contains("\"injected\":true")) {
                    Log.i(TAG, "[auto] payload jalan, tampilkan overlay");
                    showOverlay();
                } else {
                    Log.i(TAG, "[auto] inject selesai tanpa status payload");
                }
            } finally {
                injecting = false;
            }
        }).start();
    }

    /** Hapus jejak .so staging HANYA bila game benar-benar mati dan tidak ada
     *  inject yang sedang berjalan. Verifikasi ulang tepat sebelum hapus agar
     *  tidak balapan dengan auto-inject yang mungkin baru men-stage ulang
     *  (game dibuka lagi dalam jeda poll). Bila dilewati, deadTicks di-reset
     *  agar dicoba lagi 2 poll berikutnya. */
    private void cleanupStagingIfGameDead(String pkg) {
        if (injecting || RootUtil.pidOf(pkg) > 0) {
            Log.i(TAG, "cleanup staging ditunda (inject jalan / game hidup lagi)");
            deadTicks = 0;
            return;
        }
        ModInjector.cleanupStaging(s -> Log.i(TAG, "[cleanup] " + s));
    }

    private void showOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "[auto] izin overlay belum granted, lewati tampil overlay");
            return;
        }
        try {
            Intent i = new Intent(this, OverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (IllegalStateException | SecurityException e) {
            // Android 12+: start FGS dari background bisa ditolak sistem
            Log.w(TAG, "[auto] gagal start overlay: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        if (handler != null && poller != null) handler.removeCallbacks(poller);
        stopService(new Intent(this, OverlayService.class));
        Log.i(TAG, "WatchService berhenti");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
