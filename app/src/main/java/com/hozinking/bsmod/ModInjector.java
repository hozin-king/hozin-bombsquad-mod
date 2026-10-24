package com.hozinking.bsmod;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileWriter;

/**
 * Logika inject payload ke game — dipakai MainActivity (tombol manual)
 * dan WatchService (auto-inject). Pesan log dipertahankan sama persis
 * seperti alur manual sebelumnya.
 */
public final class ModInjector {
    private ModInjector() {}

    public static final String PREFS = "bsmod";
    public static final String CMD_PATH = "/data/local/tmp/bs_mod_cmd.json";
    public static final String CONF_TMP = "/data/local/tmp/bs_mod_config.json";
    public static final String STATUS_PATH = "/data/local/tmp/bs_mod_status.json";
    public static final String BOOT_PATH = "/data/local/tmp/bs_mod_boot.json";
    public static final String INJECT_RESULT_PATH = "/data/local/tmp/bs_mod_inject.json";
    // Probe minimal (diagnostik): constructor tulis breadcrumb + status,
    // tanpa Dobby/scan/hook. Untuk isolasi sumber kegagalan inject.
    public static final String PROBE_STATUS_PATH = "/data/local/tmp/bs_mod_probe.json";
    public static final String PROBE_BOOT_PATH = "/data/local/tmp/bs_mod_probe_boot.json";
    // Staging payload di /data/local/tmp agar BISA DIBACA proses game saat
    // dlopen. Direktori privat /data/app/... tidak bisa dibaca UID aplikasi
    // lain (SELinux MLS) -> dlopen return NULL.
    public static final String PAYLOAD_TMP_PATH = "/data/local/tmp/bs_payload.so";
    public static final String PROBE_TMP_PATH = "/data/local/tmp/bs_payload_probe.so";
    // Dependensi DT_NEEDED libpayload.so/libpayload_probe.so: libc++_shared.so
    // (wajib — game BS tidak membundelnya; readelf: NEEDED) + libdobby.so
    // (jaga-jaga). Linker bionic mencari dependensi di direktori yang sama
    // dengan library yang di-dlopen, jadi ditaruh berdampingan di /data/local/tmp.
    public static final String CPP_SHARED_TMP_PATH = "/data/local/tmp/libc++_shared.so";
    public static final String DOBBY_TMP_PATH = "/data/local/tmp/libdobby.so";

    /** Kunci anti-rebutan: hanya SATU alur inject yang boleh jalan dalam satu
     *  waktu (manual LAUNCH+INJECT vs auto-inject WatchService vs probe).
     *  Tanpa ini dua thread berebut PTRACE_ATTACH ke pid game yang sama ->
     *  yang kalah dapat "Operation not permitted" (sudah di-trace pihak lain).
     *  Flag `injecting` milik WatchService tidak cukup karena alur manual
     *  tidak pernah menyentuhnya. */
    private static final java.util.concurrent.atomic.AtomicBoolean INJECT_LOCK =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Listener log per langkah (MainActivity: tampil di log view; WatchService: logcat). */
    public interface LogListener {
        void log(String s);
    }

    public static final class InjectResult {
        /** true bila injector sempat dieksekusi (belum tentu pola OK). */
        public final boolean injected;
        /** Isi bs_mod_status.json ("" bila tidak muncul). */
        public final String statusJson;
        /** true bila terdeteksi game update dibanding catatan sebelumnya. */
        public final boolean updateDetected;
        /** Baris info game untuk tvGame (null bila GameInfo gagal). */
        public final String gameInfoLine;
        /** Pesan error bila gagal sebelum inject (null bila tidak ada). */
        public final String error;

        InjectResult(boolean injected, String statusJson, boolean updateDetected,
                     String gameInfoLine, String error) {
            this.injected = injected;
            this.statusJson = statusJson;
            this.updateDetected = updateDetected;
            this.gameInfoLine = gameInfoLine;
            this.error = error;
        }
    }

    /**
     * Jalankan seluruh alur inject. BLOCKING — panggil dari background thread.
     * Anti-rebutan: bila ada alur inject lain sedang berjalan, langsung lewati.
     * @param waitSecs berapa lama menunggu status pola muncul.
     */
    public static InjectResult inject(Context ctx, String pkg, LogListener L, int waitSecs) {
        if (!INJECT_LOCK.compareAndSet(false, true)) {
            L.log("Inject sudah berjalan di alur lain — lewati (anti-rebutan attach).");
            return new InjectResult(false, "", false, null, "inject sudah berjalan");
        }
        try {
            return injectLocked(ctx, pkg, L, waitSecs);
        } finally {
            INJECT_LOCK.set(false);
        }
    }

    private static InjectResult injectLocked(Context ctx, String pkg, LogListener L, int waitSecs) {
        L.log("Cek root...");
        if (!RootUtil.isRooted()) {
            L.log("GAGAL: HP tidak root / su ditolak.");
            return new InjectResult(false, "", false, null, "HP tidak root / su ditolak.");
        }
        L.log("Root OK.");

        // ---- info game + deteksi update ----
        boolean updateDetected = false;
        String gameInfoLine = null;
        try {
            GameInfo gi = GameInfo.read(ctx, pkg);
            gameInfoLine = "Game v" + gi.versionName + " (code " + gi.versionCode + ")\n"
                    + "libmain.so: " + (gi.libSha256.isEmpty()
                        ? "(tidak ketemu di APK)"
                        : gi.libSha256.substring(0, 16) + "...");
            L.log("Game: v" + gi.versionName + " code " + gi.versionCode);

            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String oldVc = p.getString("game_vc", "");
            String oldSha = p.getString("game_sha", "");
            String newVc = String.valueOf(gi.versionCode);
            if (!oldVc.isEmpty() && (!oldVc.equals(newVc) || !oldSha.equals(gi.libSha256))) {
                L.log("GAME UPDATE terdeteksi (" + oldVc + " -> " + newVc + "). Pola di-scan ulang.");
                updateDetected = true;
            } else if (oldVc.isEmpty()) {
                L.log("Versi game dicatat (code " + newVc + ").");
            } else {
                L.log("Versi game sama seperti sebelumnya.");
            }
            p.edit().putString("game_vc", newVc).putString("game_sha", gi.libSha256).apply();
        } catch (Exception e) {
            L.log("Info game gagal (" + e.getMessage() + ") — lanjut inject.");
        }

        // ---- cari PID; auto-launch game bila belum jalan (HP root) ----
        int pid = RootUtil.pidOf(pkg);
        if (pid <= 0) {
            L.log("Game belum jalan — dibuka otomatis...");
            if (launchGame(pkg, L)) {
                pid = RootUtil.pidOf(pkg);
            }
            if (pid <= 0) {
                L.log("Game gagal dibuka otomatis. Buka manual lalu ulangi.");
                return new InjectResult(false, "", updateDetected, gameInfoLine,
                        "Game gagal dibuka otomatis.");
            }
            // tunggu libmain.so ke-map sebelum inject (diet v1.1: cek
            // tiap 2 detik, total tetap ~10 detik — bukan tiap detik).
            L.log("Tunggu libmain.so dimuat...");
            boolean mapped = false;
            for (int i = 0; i < 5; i++) {
                RootUtil.Result c = RootUtil.su(
                        "grep -c libmain.so /proc/" + pid + "/maps 2>/dev/null");
                if (c.code == 0) {
                    try {
                        if (Integer.parseInt(c.out.trim()) > 0) { mapped = true; break; }
                    } catch (Exception ignored) { /* lanjut poll */ }
                }
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { /* abaikan */ }
            }
            if (!mapped) {
                L.log("libmain.so belum terdeteksi — tunggu 3 detik lagi.");
                try { Thread.sleep(3000); } catch (InterruptedException ignored) { /* abaikan */ }
            }
        }
        L.log("PID target: " + pid);

        // ---- tulis config + cmd awal (SEMUA fitur OFF, user nyalakan manual
        // via overlay; payload menolak ON bila gate tertutup / pola belum ada) ----
        String conf = "{\"mode\":\"root\",\"target_package\":\"" + pkg + "\","
                + "\"god_mode\":false,\"speed\":false,"
                + "\"grav_moon\":false,\"grav_zero\":false,\"grav_heavy\":false,"
                + "\"scale_giant\":false,\"scale_mini\":false,"
                + "\"blast_big\":false,\"punch_super\":false,\"timescale_slow\":false,"
                + "\"bot_dumb\":false,\"bot_frozen\":false,"
                + "\"bomb_teleport_sticky\":false}";
        try {
            FileWriter w = new FileWriter(new File(ctx.getFilesDir(), "config.json"));
            w.write(conf);
            w.close();
        } catch (Exception e) {
            L.log("Gagal tulis config lokal: " + e.getMessage());
        }
        if (!RootUtil.writeRootFile(CONF_TMP, conf)) {
            L.log("Gagal tulis config tmp.");
            return new InjectResult(false, "", updateDetected, gameInfoLine,
                    "Gagal tulis config tmp.");
        }
        if (!RootUtil.writeRootFile(CMD_PATH, conf)) {
            L.log("Gagal tulis cmd awal.");
            return new InjectResult(false, "", updateDetected, gameInfoLine,
                    "Gagal tulis cmd awal.");
        }
        // File yang DITULIS payload (berjalan sebagai UID game, bukan root)
        // harus sudah ada dengan mode 666: UID game tidak bisa MEMBUAT file
        // baru di /data/local/tmp (direktori hanya boleh di-traverse),
        // tapi bisa menulis ke file yang sudah ada dan terbuka untuk semua.
        for (String fp : new String[]{STATUS_PATH, BOOT_PATH,
                PROBE_STATUS_PATH, PROBE_BOOT_PATH}) {
            RootUtil.su("touch '" + fp + "' && : > '" + fp + "' && chmod 666 '" + fp + "'");
        }
        RootUtil.su("rm -f " + INJECT_RESULT_PATH);
        L.log("Config ditulis.");

        // ---- inject ----
        String libDir = ctx.getApplicationInfo().nativeLibraryDir;
        String injector = libDir + "/libinjector.so";
        String nativePayload = libDir + "/libpayload.so";
        // .so harus ter-extract sebagai file nyata (perlu extractNativeLibs="true"
        // saat install baru); path mengandung ~~ dan == sehingga di-quote.
        RootUtil.Result chk = RootUtil.su("test -f '" + injector + "' || echo MISSING");
        if (chk.out.contains("MISSING")) {
            L.log("GAGAL: libinjector.so tidak ada di " + libDir);
            return new InjectResult(false, "", updateDetected, gameInfoLine,
                    "libinjector.so tidak ada — uninstall aplikasi ini lalu install ulang APK baru.");
        }
        // PAKSA: copy payload ke /data/local/tmp agar BISA DIBACA proses game
        // saat dlopen (direktori privat /data/app/... tak bisa dibaca UID lain).
        String tmpPayload = stagePayload(ctx, "libpayload.so", PAYLOAD_TMP_PATH, L);
        String tmpProbe = stagePayload(ctx, "libpayload_probe.so", PROBE_TMP_PATH, L);
        // Stage dependensi DT_NEEDED berdampingan di /data/local/tmp agar
        // linker menemukannya saat dlopen (tidak fatal bila tidak ada).
        stageDep(ctx, "libc++_shared.so", CPP_SHARED_TMP_PATH, L);
        stageDep(ctx, "libdobby.so", DOBBY_TMP_PATH, L);
        if (tmpPayload == null) {
            L.log("GAGAL: libpayload.so tidak ada di " + libDir);
            return new InjectResult(false, "", updateDetected, gameInfoLine,
                    "libpayload.so tidak ada — uninstall aplikasi ini lalu install ulang APK baru.");
        }
        RootUtil.su("chmod 755 '" + injector + "' '" + nativePayload + "'");
        // catat waktu percobaan inject — dipakai overlay untuk deteksi
        // "5 detik tanpa status file = payload tidak jalan"
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("inject_attempt_ms", System.currentTimeMillis()).apply();
        // Retry paksa hingga 3x (jeda ~1 dtk): /data/local/tmp dulu, lalu
        // fallback path native-lib, lalu /data/local/tmp lagi. Tiap percobaan
        // log: path yang dipakai + hasil + dlerror.
        String[] tryPaths = { tmpPayload, nativePayload, tmpPayload };
        String dlres = "";
        String dlerror = "";
        for (int a = 0; a < tryPaths.length; a++) {
            if (a > 0) sleep1s();
            String ppath = tryPaths[a];
            L.log("Inject payload ke pid " + pid + " (percobaan " + (a + 1)
                    + "/3): " + ppath);
            RootUtil.Result r = RootUtil.su("'" + injector + "' " + pid + " '" + ppath + "'");
            L.log(r.out.isEmpty() ? "(injector selesai tanpa output)" : r.out);
            // Hasil analisis remote-dlopen oleh injector (ditulis sebagai root)
            RootUtil.Result ir = RootUtil.su("cat " + INJECT_RESULT_PATH + " 2>/dev/null");
            dlres = "";
            dlerror = "";
            if (ir.code == 0 && !ir.out.trim().isEmpty()) {
                L.log("Hasil injector: " + ir.out.trim());
                dlres = jsonVal(ir.out, "dlopen");
                dlerror = jsonVal(ir.out, "dlerror");
                if (!dlerror.isEmpty()) L.log("dlerror: " + dlerror);
            }
            if ("ok".equals(dlres)) break;
            if ("crash".equals(dlres)) break; // crash = mekanisme rusak, retry sia-sia
            // "null" / lainnya -> lanjut percobaan berikutnya
        }
        if ("crash".equals(dlres) || "null".equals(dlres) || dlres.isEmpty()) {
            // Fail fast: jangan buang 25 detik menunggu status yang takkan datang
            L.log("GAGAL: injector melaporkan dlopen=" + dlres
                    + (dlerror.isEmpty() ? "." : " (" + dlerror + ")"));
            runProbe(ctx, pid, tmpProbe, L);
            return new InjectResult(true, "", updateDetected, gameInfoLine,
                    "dlopen=" + dlres + " (lihat hasil probe di bawah)");
        }

        // ---- tunggu status pola ----
        // Diet v1.1: cek tiap 2 detik (dulu tiap detik), total tetap
        // ~waitSecs detik.
        L.log("Tunggu status pola (maks " + waitSecs + " dtk)...");
        int checks = (waitSecs + 1) / 2;
        for (int i = 0; i < checks; i++) {
            RootUtil.Result c = RootUtil.su("cat " + STATUS_PATH + " 2>/dev/null");
            if (c.code == 0 && !c.out.isEmpty() && c.out.contains("\"injected\":true")) {
                L.log("Status: " + c.out);
                return new InjectResult(true, c.out, updateDetected, gameInfoLine, null);
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                break;
            }
        }
        L.log("Status tidak muncul. Cek logcat tag 'BsMod'.");
        // Baca breadcrumb payload — menunjukkan tahap terakhir yang sempat dimulai
        RootUtil.Result b = RootUtil.su("cat " + BOOT_PATH + " 2>/dev/null");
        if (b.code == 0 && !b.out.trim().isEmpty()) {
            L.log("Boot payload terakhir: " + bootStage(b.out));
        } else {
            L.log("Boot payload terakhir: (tidak ada — payload tidak pernah jalan)");
        }
        // Isolasi: payload utama timeout tanpa status -> coba probe minimal
        runProbe(ctx, pid, tmpProbe, L);
        return new InjectResult(true, "", updateDetected, gameInfoLine, null);
    }

    /** Copy .so dari nativeLibraryDir ke /data/local/tmp (sebagai root) agar
     *  bisa DIBACA oleh proses game saat dlopen. Direktori privat aplikasi
     *  (/data/app/...) tidak bisa dibaca UID lain karena SELinux.
     *  @return path tujuan bila sukses, null bila file tidak ada / copy gagal. */
    private static String stagePayload(Context ctx, String name, String dest,
                                       LogListener L) {
        String src = ctx.getApplicationInfo().nativeLibraryDir + "/" + name;
        RootUtil.Result chk = RootUtil.su("test -f '" + src + "' || echo MISSING");
        if (chk.out.contains("MISSING")) return null;
        RootUtil.Result c = RootUtil.su(
                "cp -f '" + src + "' '" + dest + "' && chmod 644 '" + dest + "'");
        if (c.code != 0) {
            L.log("stagePayload: GAGAL copy " + name + " -> " + dest);
            return null;
        }
        RootUtil.Result v = RootUtil.su("ls -l '" + dest + "' 2>/dev/null");
        L.log("stagePayload: " + name + " -> " + dest + " (" + v.out.trim() + ")");
        return dest;
    }

    /** Stage SATU file dependensi DT_NEEDED (libc++_shared.so, libdobby.so).
     *  Tidak fatal: bila file tidak ada di nativeLibraryDir, log warning
     *  yang jelas lalu lanjut (inject tetap dicoba). */
    private static void stageDep(Context ctx, String name, String dest,
                                 LogListener L) {
        if (stagePayload(ctx, name, dest, L) == null) {
            L.log("stageDep: WARNING " + name + " tidak ada di nativeLibraryDir"
                    + " — lanjut tanpa itu.");
        }
    }

    /** Semua file staging di /data/local/tmp (payload + dependensi). */
    public static final String[] STAGING_FILES = {
            PAYLOAD_TMP_PATH, PROBE_TMP_PATH, CPP_SHARED_TMP_PATH, DOBBY_TMP_PATH };

    /** Hapus semua file staging di /data/local/tmp (sebagai root) agar tidak
     *  meninggalkan jejak .so. Aman dipanggil kapan saja: hanya rm file,
     *  tidak menyentuh proses. Unlink file yang sedang di-mmap game tidak
     *  merusak mapping yang sudah ter-load. */
    public static void cleanupStaging(LogListener L) {
        StringBuilder sb = new StringBuilder("rm -f");
        for (String f : STAGING_FILES) sb.append(" '").append(f).append("'");
        RootUtil.Result r = RootUtil.su(sb.toString());
        L.log("cleanup staging /data/local/tmp: "
                + (r.code == 0 ? "OK (jejak .so dihapus)"
                               : "gagal (code=" + r.code + ")"));
    }

    /** Inject payload PROBE minimal ke pid yang sama untuk isolasi masalah.
     *  Probe JALAN + payload utama tidak -> init payload utama yang rusak.
     *  Probe juga TIDAK jalan -> mekanisme dlopen/injector yang rusak. */
    private static void runProbe(Context ctx, int pid, String probeTmpPath,
                                 LogListener L) {
        if (probeTmpPath == null) {
            L.log("Probe: libpayload_probe.so tidak tersedia (lewati).");
            return;
        }
        String injector = ctx.getApplicationInfo().nativeLibraryDir + "/libinjector.so";
        L.log("Probe: inject " + probeTmpPath + " ke pid " + pid + "...");
        RootUtil.Result r = RootUtil.su("'" + injector + "' " + pid + " '" + probeTmpPath + "'");
        for (String line : r.out.split("\n")) {
            String t = line.trim();
            if (t.contains("ERROR") || t.contains("dlopen target")
                    || t.contains("dlerror")) L.log("Probe: " + t);
        }
        boolean alive = false;
        for (int i = 0; i < 3; i++) {
            RootUtil.Result c = RootUtil.su("cat " + PROBE_STATUS_PATH + " 2>/dev/null");
            if (c.code == 0 && c.out.contains("\"probe\":true")) { alive = true; break; }
            // Diet v1.1: cek tiap 2 detik (dulu tiap detik).
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { /* abaikan */ }
        }
        if (alive) {
            L.log("Probe: JALAN (dlopen+constructor OK)"
                    + " -> masalah ada di init payload utama (Dobby/scan).");
        } else {
            RootUtil.Result b = RootUtil.su("cat " + PROBE_BOOT_PATH + " 2>/dev/null");
            String extra = (b.code == 0 && !b.out.trim().isEmpty())
                    ? " (boot: " + b.out.trim() + ")" : "";
            L.log("Probe: GAGAL" + extra
                    + " -> mekanisme dlopen/injector yang bermasalah.");
        }
    }

    /** Ambil nilai string untuk key pada JSON sederhana {"k":"v",...}. */
    private static String jsonVal(String json, String key) {
        String pat = "\"" + key + "\":\"";
        int i = json.indexOf(pat);
        if (i < 0) return "";
        int s = i + pat.length();
        int e = json.indexOf('"', s);
        return (e > s) ? json.substring(s, e) : "";
    }

    /** Ambil nilai "stage" (+ "base" bila ada) dari JSON breadcrumb. */
    private static String bootStage(String json) {
        String key = "\"stage\":\"";
        int i = json.indexOf(key);
        if (i < 0) return "?";
        int s = i + key.length();
        int e = json.indexOf('"', s);
        String stage = (e > s) ? json.substring(s, e) : "?";
        String bk = "\"base\":\"";
        int j = json.indexOf(bk);
        if (j >= 0) {
            int s2 = j + bk.length();
            int e2 = json.indexOf('"', s2);
            if (e2 > s2) stage += " (" + json.substring(s2, e2) + ")";
        }
        return stage;
    }

    /** Buka game via root: resolve launcher activity lalu `am start -n`;
     *  fallback `monkey` bila resolve gagal. Poll pidOf maks 20 detik.
     *  @return true bila PID game muncul. */
    private static boolean launchGame(String pkg, LogListener L) {
        String safe = pkg.replaceAll("[^a-zA-Z0-9_.]", "");
        if (safe.isEmpty()) return false;
        RootUtil.Result r = RootUtil.su(
                "cmd package resolve-activity --brief -a android.intent.action.MAIN"
                + " -c android.intent.category.LAUNCHER " + safe + " | tail -1");
        String comp = r.out.trim();
        if (r.code == 0 && comp.contains("/")) {
            L.log("Buka game: " + comp);
            RootUtil.su("am start -n " + comp);
        } else {
            L.log("Resolve activity gagal — pakai monkey.");
            RootUtil.su("monkey -p " + safe + " -c android.intent.category.LAUNCHER 1");
        }
        for (int i = 0; i < 10; i++) {
            int pid = RootUtil.pidOf(safe);
            if (pid > 0) {
                L.log("Game jalan (pid " + pid + ").");
                return true;
            }
            // Diet v1.1: cek tiap 2 detik, total tetap ~20 detik.
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { /* abaikan */ }
        }
        return false;
    }

    private static void sleep1s() {
        try { Thread.sleep(1000); } catch (InterruptedException ignored) { /* abaikan */ }
    }
}
