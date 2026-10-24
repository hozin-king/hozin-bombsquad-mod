// Hozin BombSquad Mod v1.0 — payload (root injector, pola HCR).
//
// Dijalankan di DALAM proses game BombSquad (net.froemling.bombsquad)
// via dlopen remote oleh injector. Target: libmain.so (Ballistica C++).
//
// v1.0 = FONDASI + GATE, BUKAN fitur jadi:
//  - Offset/pattern runtime libmain.so BELUM terverifikasi (REA menyatakan
//    butuh riset offset di HP root; libmain.so stripped, tanpa simbol
//    debug). Karena itu SEMUA slot pola berstatus "menunggu-riset" dan
//    apply_feature() TIDAK PERNAH menulis memori game sampai riset
//    mengisi pola yang terverifikasi. Ini disengaja (aturan no-fake-toggle):
//    toggle yang tidak bisa jalan tidak boleh pura-pura jalan.
//  - Yang v1.0 SUDAH lakukan nyata: handshake status, baca cmd.json,
//    dan GATE SESI runtime (fail-closed, lihat gate_check()).
//
// GATE SESI (satu boolean penjaga semua fitur):
//  - isLocalSession() dievaluasi ulang tiap poll cmd (~1 detik).
//  - Sinyal: koneksi jaringan proses game sendiri (/proc/self/net/*).
//    Main lokal (vs bot / satu perangkat) tidak butuh jaringan; party
//    online / turnamen / liga butuh koneksi ke master server / peer.
//  - Aturan fail-closed:
//      * ada koneksi TCP ESTABLISHED / soket UDP ke alamat remote
//        non-loopback -> "online" -> SEMUA fitur dipaksa OFF.
//      * file /proc/self/net/* tidak bisa dibaca -> "tak-pasti" -> OFF.
//      * tidak ada koneksi remote sama sekali -> "lokal" -> toggle boleh
//        berlaku (tetap menunggu pola riset per fitur).
//  - Keterbatasan jujur: koneksi akun/sinkronisasi (master server) atau
//    iklan/Crashlytics yang masih nyangkut saat main lokal bisa membuat
//    gate tertutup secara konservatif. Itu arah aman yang benar; riset
//    perangkat lanjutan akan memilah endpoint akun vs endpoint party.
//  - Gate ditegakkan di payload (bukan cuma label UI): permintaan ON di
//    cmd.json diabaikan total bila gate != lokal, dan fitur yang sempat
//    aktif dipaksa nonaktif saat gate menutup di tengah sesi.
//
// YANG TIDAK ADA DI PAYLOAD INI (dan tidak akan pernah ditambahkan):
//  unlock karakter/map/mini-game, unlimited ticket/token, Pro/Gold Pass,
//  remove_ads, cheat party publik, cheat turnamen/liga — semuanya
//  server-side / bypass pembelian / merugikan pemain asli.
//
// File ditulis sebagai UID proses game. Aplikasi (root) membuat file-file
// ini lebih dulu dengan mode 666 agar bisa ditulis UID game.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <android/log.h>
#include <dlfcn.h>
#include <link.h>

#include "patterns.h"

#define TAG "BSMOD"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define BOOT_PATH   "/data/local/tmp/bs_mod_boot.json"
#define STATUS_PATH "/data/local/tmp/bs_mod_status.json"
#define CMD_PATH    "/data/local/tmp/bs_mod_cmd.json"

static uintptr_t g_lib_base = 0;
static size_t g_lib_size = 0;
static bool g_lib_found = false;

// ---------- util file ----------

static void wfile(const char *path, const char *data) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) return;
    ssize_t n = (ssize_t)strlen(data);
    ssize_t w = write(fd, data, (size_t)n);
    (void)w;
    close(fd);
}

static bool read_file(const char *path, char *buf, size_t bufsz) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return false;
    ssize_t n = read(fd, buf, bufsz - 1);
    close(fd);
    if (n <= 0) return false;
    buf[n] = '\0';
    return true;
}

// "key":true dalam JSON sederhana.
static bool json_true(const char *json, const char *key) {
    char pat[64];
    snprintf(pat, sizeof(pat), "\"%s\":true", key);
    return strstr(json, pat) != NULL;
}

// ---------- cari libmain.so ----------

static int phdr_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size; (void)data;
    if (info->dlpi_name && strstr(info->dlpi_name, "libmain.so")) {
        g_lib_base = (uintptr_t)info->dlpi_addr;
        return 1; // stop
    }
    return 0;
}

static void find_libmain() {
    dl_iterate_phdr(phdr_cb, NULL);
    if (g_lib_base != 0) {
        g_lib_found = true;
        // Perkiraan ukuran dari /proc/self/maps (segmen r-xp + rw).
        FILE *f = fopen("/proc/self/maps", "r");
        if (f) {
            char line[512];
            uintptr_t lo = 0, hi = 0;
            while (fgets(line, sizeof(line), f)) {
                if (!strstr(line, "libmain.so")) continue;
                uintptr_t a, b;
                if (sscanf(line, "%zx-%zx", &a, &b) == 2) {
                    if (lo == 0 || a < lo) lo = a;
                    if (b > hi) hi = b;
                }
            }
            fclose(f);
            if (hi > lo) g_lib_size = hi - lo;
        }
    }
}

// ---------- GATE SESI ----------
// Kembalikan: 0 = lokal, 1 = online, 2 = tak-pasti (fail-closed -> OFF).
static bool is_loopback_v4(const char *hexaddr) {
    // /proc/net/tcp menulis alamat IPv4 little-endian hex:
    // 127.0.0.1 -> "0100007F".
    return strcmp(hexaddr, "0100007F") == 0;
}

static bool is_loopback_v6(const char *hexaddr) {
    // ::1 dalam format /proc/net/tcp6.
    return strcmp(hexaddr, "00000000000000000000000001000000") == 0;
}

static int gate_check_file(const char *path, bool is_tcp, bool is_v6) {
    FILE *f = fopen(path, "r");
    if (!f) return 2; // tidak bisa dibaca -> tak-pasti
    char line[1024];
    int result = 0;
    bool first = true;
    while (fgets(line, sizeof(line), f)) {
        if (first) { first = false; continue; } // header
        // Format: sl local_address rem_address st ...
        char local[128], rem[128], st[16];
        if (sscanf(line, "%*d: %127s %127s %15s", local, rem, st) < 3) continue;
        // rem_address = "IP:PORT" (hex). Pisahkan IP-nya.
        char *colon = strrchr(rem, ':');
        if (!colon) continue;
        *colon = '\0';
        if (strcmp(rem, "00000000") == 0
                || strcmp(rem, "00000000000000000000000000000000") == 0) {
            continue; // belum terhubung / unspecified -> abaikan
        }
        if (is_v6 ? is_loopback_v6(rem) : is_loopback_v4(rem)) continue;
        if (is_tcp) {
            if (strcmp(st, "01") != 0) continue; // hanya ESTABLISHED
        }
        // Ada koneksi/soket ke remote non-loopback -> online.
        result = 1;
        break;
    }
    fclose(f);
    return result;
}

static int gate_check() {
    int r;
    r = gate_check_file("/proc/self/net/tcp", true, false);
    if (r != 0) return r;
    r = gate_check_file("/proc/self/net/tcp6", true, true);
    if (r != 0) return r;
    r = gate_check_file("/proc/self/net/udp", false, false);
    if (r != 0) return r;
    r = gate_check_file("/proc/self/net/udp6", false, true);
    if (r != 0) return r;
    return 0; // lokal
}

static const char *gate_str(int g) {
    return g == 0 ? "lokal" : (g == 1 ? "online" : "tak-pasti");
}

// ---------- fitur ----------
// Setiap fitur punya slot pola di patterns.h. Selama slot berstatus
// PATTERN_UNRESEARCHED, apply_feature() tidak menyentuh memori game dan
// melaporkan "riset". Ini fondasi yang siap diisi hasil riset runtime
// (pattern-scan seperti pola HCR), bukan klaim fitur sudah jalan.
static const char *g_feature_keys[BS_FEATURE_COUNT] = {
    "god_mode", "speed",
    "grav_moon", "grav_zero", "grav_heavy",
    "scale_giant", "scale_mini",
    "blast_big", "punch_super", "timescale_slow",
    "bot_dumb", "bot_frozen",
    "bomb_teleport_sticky"
};

static bool g_feature_on[BS_FEATURE_COUNT] = { false };

// Alur bom teleport lengket (didokumentasikan agar riset lanjutan tinggal
// mengisi resolver alamat; TIDAK dieksekusi tanpa offset terverifikasi):
//   1. Saat bom dilempar pemain, cari aktor musuh terdekat dari daftar
//      aktor scene lokal (posisi tiap aktor dibaca dari memori game).
//   2. Tulis posisi bom = posisi musuh terdekat, kecepatan = 0.
//   3. Paksa tipe bom = sticky bawaan game (mekanik asli, bukan ciptaan
//      mod) sehingga bom menempel lalu meledak dengan fuse normal.
//   Tanpa actor-list & bomb-object offset yang terverifikasi, langkah
//   1-3 tidak dijalankan — fungsi ini hanya penanda status.
static const char *bomb_teleport_tick() {
    if (bs_pattern_status(BS_F_BOMB_TELEPORT_STICKY) != PATTERN_FOUND)
        return "riset";
    return "aktif"; // slot terisi riset -> implementasi tick di sini
}

static void apply_features(const char *cmd, int gate) {
    for (int i = 0; i < BS_FEATURE_COUNT; i++) {
        bool want = json_true(cmd, g_feature_keys[i]);
        if (gate != 0) want = false; // gate tertutup -> paksa OFF
        if (!want) { g_feature_on[i] = false; continue; }
        if (bs_pattern_status(i) != PATTERN_FOUND) {
            g_feature_on[i] = false; // pola belum ada -> tidak diterapkan
            continue;
        }
        g_feature_on[i] = true;
    }
}

static void build_features_str(char *buf, size_t bufsz) {
    size_t off = 0;
    for (int i = 0; i < BS_FEATURE_COUNT; i++) {
        const char *st;
        if (g_feature_on[i]) st = "aktif";
        else if (bs_pattern_status(i) == PATTERN_FOUND) st = "mati";
        else st = "riset";
        int n = snprintf(buf + off, bufsz - off, "%s%s=%s",
                         i ? " " : "", g_feature_keys[i], st);
        if (n < 0 || (size_t)n >= bufsz - off) break;
        off += (size_t)n;
    }
    (void)bomb_teleport_tick; // didokumentasikan; dipakai saat slot terisi
}

// ---------- thread utama payload ----------

static void write_status(int gate) {
    char feat[1024];
    build_features_str(feat, sizeof(feat));
    char st[1600];
    snprintf(st, sizeof(st),
             "{\"injected\":true,\"lib_found\":%s,\"lib_base\":\"0x%zx\","
             "\"lib_size\":%zu,\"gate\":\"%s\",\"features\":\"%s\","
             "\"note\":\"v1.0 eksperimental - menunggu uji HP + riset offset\"}\n",
             g_lib_found ? "true" : "false",
             g_lib_base, g_lib_size, gate_str(gate), feat);
    wfile(STATUS_PATH, st);
}

static void *payload_thread(void *arg) {
    (void)arg;
    char cmd[2048];
    for (int tick = 0; ; tick++) {
        int gate = gate_check();
        if (read_file(CMD_PATH, cmd, sizeof(cmd))) {
            apply_features(cmd, gate);
        }
        write_status(gate);
        (void)tick;
        sleep(1);
    }
    return NULL;
}

__attribute__((constructor))
static void bs_init(void) {
    wfile(BOOT_PATH, "{\"stage\":\"bs_loaded\"}\n");
    LOGI("payload BombSquad jalan (pid=%d)", (int)getpid());
    find_libmain();
    LOGI("libmain.so: %s base=0x%zx size=%zu",
         g_lib_found ? "ketemu" : "TIDAK ketemu", g_lib_base, g_lib_size);
    write_status(2); // status awal: tak-pasti sampai gate pertama jalan
    pthread_t t;
    if (pthread_create(&t, NULL, payload_thread, NULL) == 0)
        pthread_detach(t);
}
