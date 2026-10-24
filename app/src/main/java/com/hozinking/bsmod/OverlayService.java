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
 * SEMUA FITUR v1.1 BERLABEL EKSPERIMENTAL: offset runtime libmain.so
 * belum terverifikasi (butuh riset di HP root). Toggle TIDAK boleh ON
 * kalau payload belum jalan, gate sesi tertutup, atau pola fitur belum
 * ditemukan — guard ini ditegakkan di pushCmd() + di payload (fail-closed).
 *
 * GATE (v1.1): pintu utama = Keamanan.java (boolean isLocalSession,
 * gaya gate PvP mod Billiard). Overlay WAJIB konsultasi ke sana SEBELUM
 * mengirim perintah toggle apa pun (pushCmd): bila false, perintah
 * TIDAK dikirim dan switch dipaksa OFF. Payload menyimpan backstop
 * fail-closed sebagai jaring kedua (payload.cpp, tiap 3 detik).
 *
 * DIET v1.1: polling status 3 detik (dulu 1 detik) dan UI hanya di-update
 * bila nilai bermakna berubah (tick payload yang naik tiap siklus TIDAK
 * dihitung berubah) — tidak ada rebuild teks tiap tick.
 *
 * LAYOUT v1.2: panel floating MURNI fitur saja — judul + tombol X +
 * daftar toggle (tinggi penuh panel + scroll). SEMUA teks status/log
 * (Inject, Gate, lib, Fitur) KELUAR dari panel dan tampil di aplikasi
 * utama (MainActivity) yang memang sudah menampilkan status + log.
 * Di v1.0/v1.1 blok status kuning di bawah ScrollView memakan tinggi
 * panel dan menjepit area toggle sampai ~1 baris, plus switch kepotong
 * tepi kanan (panel 280dp tanpa padding kanan) — keduanya diperbaiki
 * di v1.2: tiap baris toggle = row horizontal (label weight 1 + Switch)
 * dengan padding kanan sehingga switch muat penuh di dalam panel.
 */
public class OverlayService extends Service {

    private static final String PREFS = "bsmod";
    private static final String CMD_PATH = "/data/local/tmp/bs_mod_cmd.json";
    private static final String STATUS_PATH = "/data/local/tmp/bs_mod_status.json";
    private static final String CH_ID = "bsmod_overlay";
    private static final int LOGO_DP = 48;
    private static final long POLL_MS = 3000; // diet v1.1 (dulu 1000)

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
    // v1.2: panel MURNI fitur — tidak ada lagi TextView status/log di sini.
    // Status + log tampil di MainActivity (tvStatus + tvLog).
    private String lastGate = ""; // diet: hanya proses bila gate berubah
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
        title.setText("Hozin BombSquad Mod v1.2");
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

        // v1.2: panel MURNI fitur — judul + X + daftar toggle saja.
        // Tidak ada banner, tidak ada blok status/log di panel.
        // (Label 🧪 di tiap toggle tetap menandai status eksperimental.)
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
            // v1.2: tiap baris toggle = row horizontal (label weight 1 +
            // Switch) dengan padding kanan — switch dijamin muat penuh
            // di dalam panel, tidak kepotong tepi kanan (cacat v1.0/v1.1).
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(2), dp(8), dp(2));
            TextView lbl = new TextView(this);
            lbl.setText(LABELS[i] + " 🧪");
            lbl.setTextColor(0xFFFFFFFF);
            lbl.setTextSize(14);
            row.addView(lbl, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            Switch sw = new Switch(this);
            sw.setChecked(st[i]);
            sw.setMinWidth(0);
            sw.setPadding(0, 0, dp(4), 0);
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
            row.addView(sw);
            content.addView(row);
        }

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
        lastGate = "";
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
            // v1.1 PINTU UTAMA: konsultasi Keamanan.java SEBELUM kirim
            // perintah toggle apa pun. Bila gate false, perintah TIDAK
            // dikirim dan switch dipaksa OFF (payload backstop juga
            // menolak di sisinya — pertahanan berlapis). Mematikan semua
            // toggle selalu boleh (tidak perlu gate).
            if (wantOn) {
                String pkg = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getString("package", "net.froemling.bombsquad");
                Keamanan.Hasil h = Keamanan.cekSesiPaket(pkg);
                if (!h.lokal) {
                    writing = false;
                    handler.post(() -> {
                        Toast.makeText(OverlayService.this,
                                "Gate Keamanan: " + h.alasan + " — toggle dipaksa OFF",
                                Toast.LENGTH_SHORT).show();
                        revertSwitches();
                    });
                    return;
                }
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
        // v1.2: panel TIDAK menampilkan status/log apa pun — polling ini
        // hanya menjaga pertahanan berlapis: bila gate menutup (online /
        // tak-pasti) sementara ada toggle ON, paksa semua toggle OFF di
        // UI (payload backstop juga menolak di sisinya). Tampilan status
        // lengkap ada di MainActivity (tvStatus + tvLog).
        if (out.isEmpty() || !out.contains("\"injected\":true")) {
            lastGate = "";
            return;
        }
        String gate = jval(out, "gate");
        if (gate.equals(lastGate)) return;
        lastGate = gate;
        if (gate.equals("online") || gate.equals("tak-pasti")) {
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
