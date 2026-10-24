package com.hozinking.bsmod;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Hozin BombSquad Mod — mod menu klasik:
 * logo kecil floating (bisa di-drag) -> tap -> panel menu.
 *
 * SEMUA FITUR v1.0 BERLABEL EKSPERIMENTAL: offset runtime libmain.so
 * belum terverifikasi (butuh riset di HP root). Toggle TIDAK boleh ON
 * kalau payload belum jalan, gate sesi tertutup, atau pola fitur belum
 * ditemukan — guard ini ditegakkan di pushCmd() + di payload (fail-closed).
 *
 * GATE: satu boolean isLocalSession di payload. Sesi online (public party,
 * turnamen, apa pun yang nyambung pemain lain/server) ATAU jenis sesi tak
 * pasti -> SEMUA fitur dipaksa OFF, apa pun posisi toggle.
 */
public class OverlayService extends Service {

    private static final String PREFS = "bsmod";
    private static final String CMD_PATH = "/data/local/tmp/bs_mod_cmd.json";
    private static final String STATUS_PATH = "/data/local/tmp/bs_mod_status.json";
    private static final String CH_ID = "bsmod_overlay";
    private static final int LOGO_DP = 48;
    private static final long POLL_MS = 1000;
    private static final long NO_STATUS_WARN_MS = 5000;

    // Urutan kunci harus sama dengan daftar fitur di payload.
    private static final String[] KEYS = {
            "god_mode", "speed",
            "grav_moon", "grav_zero", "grav_heavy",
            "scale_giant", "scale_mini",
            "blast_big", "punch_super", "timescale_slow",
            "bot_dumb", "bot_frozen",
            "bomb_teleport_sticky"
    };
    private static final String[] LABELS = {
            "God Mode", "Speed (pengali 1.5x)",
            "Gravitasi Bulan", "Gravitasi Nol", "Gravitasi Berat",
            "Karakter Raksasa", "Karakter Mini",
            "Ledakan Raksasa", "Pukulan Super", "Slow Motion",
            "Bot Bego", "Bot Beku",
            "Bom Teleport Lengket"
    };

    private WindowManager wm;
    private View logoView;
    private View panelView;
    private Switch[] switches = new Switch[KEYS.length];
    private boolean[] st = new boolean[KEYS.length];
    private TextView tvInject, tvGate, tvLib, tvFeatures;
    private Handler handler;
    private Runnable poller;
    private boolean writing = false;
    private boolean updating = false;
    private boolean panelVisible = false;
    private boolean gateToastShown = false;

    @Override
    public void onCreate() {
        super.onCreate();
        startFg();
        handler = new Handler(Looper.getMainLooper());
        buildLogo();
        poller = new Runnable() {
            @Override public void run() {
                pollStatus();
                handler.postDelayed(this, POLL_MS);
            }
        };
        handler.post(poller);
    }

    private void startFg() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(
                    CH_ID, "BombSquad Mod Overlay", NotificationManager.IMPORTANCE_MIN));
        }
        Notification.Builder nb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        Notification n = nb.setContentTitle("Hozin BombSquad Mod")
                .setContentText("Mod menu aktif — solo lokal saja")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .build();
        startForeground(1, n);
    }

    // ---------- logo floating ----------

    private void buildLogo() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        FrameLayout logo = new FrameLayout(this);
        logo.setBackgroundResource(R.drawable.logo_bg);
        TextView t = new TextView(this);
        t.setText("B");
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(20);
        t.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        logo.addView(t, lp);

        final int size = dp(LOGO_DP);
        final int[] downXY = new int[2];
        final long[] downTime = new long[1];
        final boolean[] moved = new boolean[1];
        final WindowManager.LayoutParams[] wlp = new WindowManager.LayoutParams[1];
        logoView = logo;

        logo.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (wlp[0] == null) wlp[0] = (WindowManager.LayoutParams) logoView.getLayoutParams();
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downXY[0] = (int) e.getRawX();
                        downXY[1] = (int) e.getRawY();
                        downTime[0] = SystemClock.uptimeMillis();
                        moved[0] = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) e.getRawX() - downXY[0];
                        int dy = (int) e.getRawY() - downXY[1];
                        if (!moved[0] && Math.abs(dx) + Math.abs(dy) > dp(10)) {
                            moved[0] = true;
                            if (panelVisible) hidePanel();
                        }
                        if (moved[0]) {
                            wlp[0].x += dx;
                            wlp[0].y += dy;
                            wm.updateViewLayout(logoView, wlp[0]);
                            downXY[0] = (int) e.getRawX();
                            downXY[1] = (int) e.getRawY();
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        v.performClick();
                        if (moved[0]) {
                            saveLogoPos(wlp[0].x, wlp[0].y);
                        } else if (SystemClock.uptimeMillis() - downTime[0] > 600) {
                            stopSelf(); // long-press logo = matikan layanan
                        } else {
                            togglePanel();
                        }
                        return true;
                }
                return false;
            }
        });

        WindowManager.LayoutParams p = overlayParams(size, size);
        p.gravity = Gravity.TOP | Gravity.START;
        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.x = sp.getInt("logo_x", 40);
        p.y = sp.getInt("logo_y", 200);
        wm.addView(logoView, p);
    }

    private void saveLogoPos(int x, int y) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt("logo_x", x).putInt("logo_y", y).apply();
    }

    // ---------- panel menu ----------

    private void togglePanel() {
        if (panelVisible) hidePanel();
        else showPanel();
    }

    private TextView statusRow(LinearLayout parent, String label) {
        TextView tv = new TextView(this);
        tv.setText(label + ": -");
        tv.setTextColor(0xFFFFC107);
        tv.setTextSize(12);
        parent.addView(tv);
        return tv;
    }

    private int keyIndex(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
        return -1;
    }

    private void showPanel() {
        if (panelVisible) return;

        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        lay.setBackgroundResource(R.drawable.panel_bg);
        int pad = dp(12);
        lay.setPadding(pad, pad, pad, pad);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(this);
        title.setText("Hozin BombSquad Mod v1.0");
        title.setTextColor(0xFF4CAF50);
        title.setTextSize(16);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(title, tlp);
        Button btnX = new Button(this);
        btnX.setText("X");
        btnX.setMinWidth(dp(36));
        btnX.setOnClickListener(v -> hidePanel());
        head.addView(btnX);
        lay.addView(head);

        // Banner eksperimental — jujur, bukan klaim sudah jalan.
        TextView warn = new TextView(this);
        warn.setText("EKSPERIMENTAL — menunggu uji HP + riset offset.\n"
                + "Toggle tidak akan aktif sampai pola ditemukan & sesi lokal terkonfirmasi.\n"
                + "SOLO LOKAL SAJA (vs bot / satu perangkat). Online = fitur mati.");
        warn.setTextColor(0xFFFF8A65);
        warn.setTextSize(11);
        warn.setPadding(0, dp(4), 0, dp(8));
        lay.addView(warn);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        lay.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        for (int i = 0; i < KEYS.length; i++) {
            final int idx = i;
            Switch sw = new Switch(this);
            sw.setText(LABELS[i] + " 🧪");
            sw.setTextColor(0xFFFFFFFF);
            sw.setChecked(st[i]);
            sw.setOnCheckedChangeListener((v, on) -> {
                if (updating) return;
                st[idx] = on;
                if (on) {
                    // Preset yang saling bertentangan: yang terakhir dinyalakan menang.
                    if (KEYS[idx].startsWith("grav_")) {
                        for (int j = 0; j < KEYS.length; j++) {
                            if (j != idx && KEYS[j].startsWith("grav_") && st[j]) {
                                st[j] = false;
                            }
                        }
                    }
                    if (KEYS[idx].startsWith("scale_")) {
                        for (int j = 0; j < KEYS.length; j++) {
                            if (j != idx && KEYS[j].startsWith("scale_") && st[j]) {
                                st[j] = false;
                            }
                        }
                    }
                    refreshSwitches();
                }
                pushCmd();
            });
            switches[i] = sw;
            content.addView(sw);
        }

        tvInject = statusRow(lay, "Inject");
        tvGate = statusRow(lay, "Gate sesi");
        tvLib = statusRow(lay, "libmain.so");
        tvFeatures = statusRow(lay, "Fitur");

        panelView = lay;
        int sh2 = getResources().getDisplayMetrics().heightPixels;
        WindowManager.LayoutParams p = overlayParams(dp(280), (int) (sh2 * 0.7));
        p.gravity = Gravity.TOP | Gravity.START;
        WindowManager.LayoutParams llp = (WindowManager.LayoutParams) logoView.getLayoutParams();
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        p.x = Math.max(0, Math.min(llp.x, sw - dp(280)));
        p.y = Math.max(0, Math.min(llp.y + dp(LOGO_DP + 8), sh - dp(340)));
        wm.addView(panelView, p);
        panelVisible = true;
        if (logoView != null) logoView.setVisibility(android.view.View.GONE);

        refreshStatusNow();
        pushCmd();
    }

    private void refreshSwitches() {
        updating = true;
        try {
            for (int i = 0; i < KEYS.length; i++) {
                if (switches[i] != null) switches[i].setChecked(st[i]);
            }
        } finally {
            updating = false;
        }
    }

    private void hidePanel() {
        if (!panelVisible) return;
        panelVisible = false;
        if (wm != null && panelView != null) {
            try { wm.removeView(panelView); } catch (Exception ignored) {}
            panelView = null;
        }
        if (logoView != null) logoView.setVisibility(android.view.View.VISIBLE);
        switches = new Switch[KEYS.length];
        tvInject = tvGate = tvLib = tvFeatures = null;
    }

    private WindowManager.LayoutParams overlayParams(int w, int h) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        return new WindowManager.LayoutParams(
                w, h, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
    }

    /**
     * Tulis toggle ke cmd.json via root (background thread).
     * Guard: toggle TIDAK boleh ON kalau payload belum jalan -> toast +
     * switch dikembalikan OFF. Gate sesi ditegakkan di payload (bukan di
     * sini): payload mengabaikan permintaan ON bila gate tertutup.
     */
    private void pushCmd() {
        if (writing) return;
        writing = true;
        final boolean[] cur = st.clone();
        new Thread(() -> {
            boolean wantOn = false;
            for (boolean b : cur) if (b) { wantOn = true; break; }
            RootUtil.Result c = RootUtil.su("cat " + STATUS_PATH + " 2>/dev/null");
            boolean payloadUp = c.code == 0 && !c.out.isEmpty()
                    && c.out.contains("\"injected\":true");
            if (wantOn && !payloadUp) {
                writing = false;
                handler.post(() -> {
                    Toast.makeText(OverlayService.this,
                            "payload belum aktif, tunggu inject selesai",
                            Toast.LENGTH_SHORT).show();
                    revertSwitches();
                });
                return;
            }
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < KEYS.length; i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(KEYS[i]).append("\":").append(cur[i]);
            }
            sb.append("}");
            RootUtil.writeRootFile(CMD_PATH, sb.toString());
            writing = false;
        }).start();
    }

    private void revertSwitches() {
        updating = true;
        try {
            for (int i = 0; i < KEYS.length; i++) st[i] = false;
            refreshSwitches();
        } finally {
            updating = false;
        }
    }

    // ---------- status handshake ----------

    private static String jval(String js, String key) {
        String pat = "\"" + key + "\":\"";
        int i = js.indexOf(pat);
        if (i < 0) return "";
        int s = i + pat.length();
        int e = js.indexOf('"', s);
        return e > s ? js.substring(s, e) : "";
    }

    private void setRow(TextView tv, String label, String val,
                        String good, String bad) {
        if (tv == null) return;
        tv.setText(label + ": " + (val.isEmpty() ? "-" : val));
        int color = 0xFFFFC107;
        if (val.equals(good)) color = 0xFF4CAF50;
        else if (val.equals(bad)) color = 0xFFF44336;
        tv.setTextColor(color);
    }

    private void refreshStatusNow() {
        new Thread(() -> {
            RootUtil.Result r = RootUtil.su("cat " + STATUS_PATH + " 2>/dev/null");
            final String out = (r.code == 0) ? r.out : "";
            handler.post(() -> applyStatus(out));
        }).start();
    }

    private void pollStatus() {
        if (!panelVisible) return;
        refreshStatusNow();
    }

    private void applyStatus(String out) {
        if (!panelVisible) return;
        if (out.isEmpty() || !out.contains("\"injected\":true")) {
            long attempt = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong("inject_attempt_ms", 0);
            long elapsed = attempt == 0 ? 0 : (System.currentTimeMillis() - attempt);
            if (attempt != 0 && elapsed >= NO_STATUS_WARN_MS) {
                setRow(tvInject, "Inject", "payload tidak jalan — inject gagal",
                        "", "payload tidak jalan — inject gagal");
            } else {
                setRow(tvInject, "Inject", "menunggu payload...", "", "");
            }
            setRow(tvGate, "Gate sesi", "-", "", "");
            setRow(tvLib, "libmain.so", "-", "", "");
            setRow(tvFeatures, "Fitur", "-", "", "");
            return;
        }
        setRow(tvInject, "Inject", "OK", "OK", "");
        String gate = jval(out, "gate");
        setRow(tvGate, "Gate sesi", gate, "lokal", "online");
        if (gate.equals("online") || gate.equals("tak-pasti")) {
            // Gate tertutup: paksa semua toggle OFF di UI (payload juga
            // menolak permintaan ON di sisinya — pertahanan berlapis).
            boolean hadOn = false;
            for (boolean b : st) if (b) { hadOn = true; break; }
            if (hadOn) {
                revertSwitches();
                if (!gateToastShown) {
                    gateToastShown = true;
                    Toast.makeText(this,
                            "Sesi online/tak-pasti — semua fitur dipaksa OFF",
                            Toast.LENGTH_SHORT).show();
                }
            }
        } else if (gate.equals("lokal")) {
            gateToastShown = false;
        }
        setRow(tvLib, "libmain.so", jval(out, "lib_base").isEmpty()
                ? "tidak ketemu" : "ketemu", "ketemu", "tidak ketemu");
        setRow(tvFeatures, "Fitur", jval(out, "features"), "", "");
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        if (handler != null && poller != null) handler.removeCallbacks(poller);
        hidePanel();
        if (wm != null && logoView != null) {
            try { wm.removeView(logoView); } catch (Exception ignored) {}
            logoView = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
