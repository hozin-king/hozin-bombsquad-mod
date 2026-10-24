package com.hozinking.bsmod;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Hozin BS Mod — layar utama.
 * Cek root, deteksi update game, inject payload (manual),
 * auto-inject saat game dibuka, overlay mod menu.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "bsmod";
    private static final String DEFAULT_PKG = "net.froemling.bombsquad";

    private EditText etPackage;
    private TextView tvRoot, tvGame, tvStatus, tvLog, tvOverlayWarn;
    private ScrollView svLog;
    private Switch swAuto;
    private AlertDialog loadingDlg;
    private TextView tvLoadingMsg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        etPackage = findViewById(R.id.etPackage);
        tvRoot = findViewById(R.id.tvRoot);
        tvGame = findViewById(R.id.tvGame);
        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);
        svLog = findViewById(R.id.svLog);
        tvOverlayWarn = findViewById(R.id.tvOverlayWarn);
        swAuto = findViewById(R.id.swAutoInject);

        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        etPackage.setText(p.getString("package", DEFAULT_PKG));

        Button btnInject = findViewById(R.id.btnInject);
        Button btnOverlay = findViewById(R.id.btnOverlay);
        btnInject.setOnClickListener(v -> new Thread(this::doInject).start());
        btnOverlay.setOnClickListener(v -> startOverlay());
        Button btnExit = findViewById(R.id.btnExit);
        btnExit.setOnClickListener(v -> doExit());

        // auto-inject: default ON
        swAuto.setChecked(p.getBoolean("auto_inject", true));
        swAuto.setOnCheckedChangeListener((v, on) -> {
            p.edit().putBoolean("auto_inject", on).apply();
            setWatch(on);
            log("Auto-inject " + (on ? "AKTIF" : "mati"));
        });
        if (swAuto.isChecked()) setWatch(true);

        new Thread(() -> {
            boolean ok = RootUtil.isRooted();
            runOnUiThread(() -> tvRoot.setText("Root: " + (ok ? "OK (uid=0)" : "TIDAK ADA / ditolak")));
            log("Cek root: " + (ok ? "OK" : "gagal"));
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Android 12+: izin overlay harus diaktifkan manual oleh pengguna
        boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);
        tvOverlayWarn.setVisibility(granted ? View.GONE : View.VISIBLE);
    }

    /** Inject manual — logika di ModInjector, UI update tetap sama. */
    private void doInject() {
        final String pkg = etPackage.getText().toString().trim();
        if (pkg.isEmpty()) {
            log("Isi package game dulu.");
            return;
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("package", pkg).apply();

        // Jangan tumpuk proses: kalau loading masih tampil, inject sedang jalan
        if (loadingDlg != null && loadingDlg.isShowing()) {
            toast("Inject masih berjalan — tutup loading dulu untuk mengulang");
            return;
        }
        runOnUiThread(() -> showLoading("Menyiapkan..."));
        ModInjector.LogListener L = s -> {
            log(s);
            runOnUiThread(() -> updateLoading(s));
        };
        ModInjector.InjectResult r = ModInjector.inject(this, pkg, L, 25);
        runOnUiThread(this::hideLoading);
        // Logo floating dimunculkan PAKSA setiap game jalan (apapun hasil inject)
        if (r.injected) {
            runOnUiThread(() -> forceShowOverlay());
        }

        if (r.gameInfoLine != null) {
            final String info = r.gameInfoLine;
            runOnUiThread(() -> tvGame.setText(info));
        }
        if (r.updateDetected) {
            toast("Game update terdeteksi — pola di-scan ulang");
        }
        if (r.statusJson != null && !r.statusJson.isEmpty()) {
            final String st = r.statusJson;
            runOnUiThread(() -> tvStatus.setText("Status:\n" + prettyStatus(st)));
            if (st.contains("\"gate\":\"online\"") || st.contains("\"gate\":\"tak-pasti\"")) {
                toast("Gate: sesi online/tak-pasti — semua fitur dipaksa OFF");
            } else {
                toast("Payload jalan — buka overlay untuk toggle");
            }
        }
    }

    /** Ambil nilai string JSON sederhana: "key":"value". */
    private static String jval(String js, String key) {
        String pat = "\"" + key + "\":\"";
        int i = js.indexOf(pat);
        if (i < 0) return "-";
        int s = i + pat.length();
        int e = js.indexOf('"', s);
        return e > s ? js.substring(s, e) : "-";
    }

    /** Tampilkan status JSON handshake secara ringkas. */
    private static String prettyStatus(String json) {
        return "Inject: " + (json.contains("\"injected\":true") ? "OK" : "-")
                + "\nGate sesi: " + jval(json, "gate")
                + " | libmain.so: " + (json.contains("\"lib_found\":true") ? "ketemu" : "belum")
                + "\nFitur: " + jval(json, "features")
                + "\nlib_base: " + jval(json, "lib_base")
                + "\n" + jval(json, "note");
    }

    private void startOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            toast("Izinkan 'tampil di atas aplikasi lain' dulu");
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        startOverlayService();
        toast("Overlay jalan");
    }

    private void startOverlayService() {
        Intent i = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    /** Paksa tampilkan logo floating tanpa toast/navigasi (dipakai setelah inject). */
    private void forceShowOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            log("Izin overlay belum ada — aktifkan 'tampil di atas aplikasi lain'.");
            return;
        }
        startOverlayService();
        log("Logo floating dimunculkan.");
    }

    private void setWatch(boolean on) {
        Intent i = new Intent(this, WatchService.class);
        if (on) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } else {
            stopService(i);
        }
    }

    /** Keluar total: hapus jejak .so staging (root) -> hentikan semua service
     *  -> tutup aplikasi. Unlink .so yang sedang dipakai game aman (mapping
     *  yang sudah ter-load tidak rusak). */
    private void doExit() {
        log("Keluar: bersihkan jejak staging...");
        new Thread(() -> {
            ModInjector.cleanupStaging(s -> log("[exit] " + s));
            log("[exit] hentikan WatchService + OverlayService...");
            stopService(new Intent(this, WatchService.class));
            stopService(new Intent(this, OverlayService.class));
            try { Thread.sleep(500); } catch (InterruptedException ignored) { }
            log("[exit] tutup aplikasi.");
            runOnUiThread(() -> {
                hideLoading();
                finishAffinity();
            });
        }).start();
    }

    /** Dialog loading selama proses inject — pesan mengikuti langkah terakhir. */
    private void showLoading(String msg) {
        if (loadingDlg != null && loadingDlg.isShowing()) return;
        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.HORIZONTAL);
        lay.setGravity(Gravity.CENTER_VERTICAL);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (24 * d);
        lay.setPadding(pad, pad, pad, pad);
        ProgressBar pb = new ProgressBar(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, (int) (16 * d), 0);
        lay.addView(pb, lp);
        tvLoadingMsg = new TextView(this);
        tvLoadingMsg.setText(msg);
        tvLoadingMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        lay.addView(tvLoadingMsg);
        loadingDlg = new AlertDialog.Builder(this)
                .setTitle("Hozin BS Mod")
                .setView(lay)
                .setCancelable(true)
                .create();
        // Back / tap di luar menutup dialog; proses background tetap jalan
        // sendiri sampai selesai (tidak dibunuh).
        loadingDlg.setCanceledOnTouchOutside(true);
        loadingDlg.setOnCancelListener(dlg -> {
            loadingDlg = null;
            tvLoadingMsg = null;
        });
        loadingDlg.show();
    }

    private void updateLoading(String msg) {
        if (tvLoadingMsg != null) tvLoadingMsg.setText(msg);
    }

    private void hideLoading() {
        if (loadingDlg != null && loadingDlg.isShowing()) loadingDlg.dismiss();
        loadingDlg = null;
        tvLoadingMsg = null;
    }

    private void log(String s) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        runOnUiThread(() -> {
            tvLog.append("[" + t + "] " + s + "\n");
            svLog.post(() -> svLog.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void toast(String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }
}
