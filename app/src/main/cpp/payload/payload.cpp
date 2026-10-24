// Hozin BombSquad Mod v1.1 — payload (root injector, pola HCR).
//
// Dijalankan di DALAM proses game BombSquad (net.froemling.bombsquad)
// via dlopen remote oleh injector. Target: libmain.so (Ballistica C++).
//
// PINTU UTAMA (GATE) TIDAK ADA DI FILE INI — cari di:
//   app/src/main/java/com/hozinking/bsmod/Keamanan.java
// Keamanan.isLocalSession(gamePid) adalah SATU-SATUNYA pintu utama
// boolean (gaya gate PvP mod Billiard): OverlayService + MainActivity
// WAJIB berkonsultasi ke sana sebelum mengirim perintah toggle apa pun.
// File ini hanya menyimpan BACKSTOP fail-closed: pemeriksaan gate yang
// sama logikanya, dievaluasi di dalam proses game tiap 3 detik (bukan
// tiap detik — dilonggarkan di v1.1 karena user mengeluh lag; baca
// /proc/self/net itu ringan, tapi tetap diminimalkan).
// Kalau mencari "keamanan" mod ini, buka Keamanan.java dulu —
// backstop di file ini hanya jaring pengaman kedua, bukan pintu utama.
//
// v1.1 = FONDASI + GATE YANG DIPERBAIKI, BUKAN fitur jadi:
//  - Offset/pattern runtime libmain.so BELUM terverifikasi (REA menyatakan
//    butuh riset offset di HP root; libmain.so stripped, tanpa simbol
//    debug). Karena itu SEMUA slot pola berstatus "menunggu-riset" dan
//    apply_feature() TIDAK PERNAH menulis memori game sampai riset
//    mengisi pola yang terverifikasi. Ini disengaja (aturan no-fake-toggle):
//    toggle yang tidak bisa jalan tidak boleh pura-pura jalan.
//  - Yang v1.1 perbaiki dari v1.0 (laporan uji HP 24 Okt 20:50):
//      (a) lib_size: 0 di v1.0 -> sekarang dihitung PRIMER dari segmen
//          PT_LOAD program header libmain.so (tak bergantung string path
//          di /proc/self/maps), dengan fallback parsing maps berbuffer
//          besar. Status JSON + UI menampilkan lib_size + sumbernya.
//      (b) Gate "tak-pasti" di menu utama lokal -> pintu utama pindah ke
//          Keamanan.java (Java, via root, gaya gate PvP Billiard) dan
//          klasifikasinya dipilah: koneksi sinkronisasi akun (HTTPS/443,
//          HTTP/80, DNS/53) diabaikan, hanya koneksi peer/game yang
//          memaksa "online". File net yang tidak ada (ENOENT, mis. tcp6
//          di kernel tanpa IPv6) dilewati, bukan lagi tak-pasti.
//          Fail-closed tetap: file tak-terbaca / data tak lengkap ->
//          tak-pasti -> fitur OFF. Backstop di file ini memakai logika
//          klasifikasi yang SAMA, dievaluasi tiap 3 detik.
//      (c) Diagnostik: status JSON kini membawa tick (bukti thread payload
//          masih hidup), gate_reason (alasan keputusan gate), dan
//          gate_conns (ringkasan koneksi terlihat) agar evaluasi gate
//          bisa diverifikasi dari UI, bukan ditebak.
//  - Yang v1.1 SUDAH lakukan nyata: handshake status, baca cmd.json,
//    dan GATE SESI runtime (fail-closed, lihat gate.cpp).
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
#include <errno.h>
#include <pthread.h>
#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <elf.h>

#include "patterns.h"

#define TAG "BSMOD"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define BOOT_PATH   "/data/local/tmp/bs_mod_boot.json"
#define STATUS_PATH "/data/local/tmp/bs_mod_status.json"
#define CMD_PATH    "/data/local/tmp/bs_mod_cmd.json"

static uintptr_t g_lib_base = 0;
static size_t g_lib_size = 0;
static const char *g_lib_size_src = "none";
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

// Callback dl_iterate_phdr: cari libmain.so, ambil base + ukuran dari
// segmen PT_LOAD (primer — tidak bergantung pada string path di maps).
static int phdr_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size; (void)data;
    if (info->dlpi_name && strstr(info->dlpi_name, "libmain.so")) {
        g_lib_base = (uintptr_t)info->dlpi_addr;
        uintptr_t lo = (uintptr_t)-1, hi = 0;
        const Elf64_Phdr *ph = (const Elf64_Phdr *)info->dlpi_phdr;
        for (int i = 0; i < info->dlpi_phnum; i++) {
            if (ph[i].p_type != PT_LOAD) continue;
            uintptr_t s = (uintptr_t)ph[i].p_vaddr;
            uintptr_t e = s + (uintptr_t)ph[i].p_memsz;
            if (s < lo) lo = s;
            if (e > hi) hi = e;
        }
        if (hi > lo && lo != (uintptr_t)-1) {
            g_lib_size = hi - lo;
            g_lib_size_src = "phdr";
        }
        return 1; // stop
    }
    return 0;
}

// Fallback bila ukuran dari phdr gagal: bentang mapping libmain.so di
// /proc/self/maps. Buffer 4096 (v1.0 memakai 512 — baris maps dengan
// path APK panjang bisa terpotong sehingga "libmain.so" tak terbaca
// dan ukuran jatuh ke 0).
static void find_libmain_maps_fallback() {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return;
    char line[4096];
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
    if (hi > lo) {
        g_lib_size = hi - lo;
        g_lib_size_src = "maps";
    }
}

static void find_libmain() {
    dl_iterate_phdr(phdr_cb, NULL);
    if (g_lib_base != 0) {
        g_lib_found = true;
        if (g_lib_size == 0) find_libmain_maps_fallback();
    }
}

// ---------- BACKSTOP GATE (fail-closed, inline — BUKAN pintu utama) ----------
// Pintu utama = Keamanan.java (Java, via root, gaya gate PvP Billiard).
// Backstop ini memakai logika klasifikasi yang SAMA, dievaluasi di dalam
// proses game tiap 3 detik (diet v1.1: dulu tiap detik). Bila backstop
// menutup (online/tak-pasti), toggle dipaksa OFF meski Keamanan berkata
// lokal. State: 0 = lokal, 1 = online, 2 = tak-pasti.
static int g_gate_state = 2;
static char g_gate_reason[256] = "menunggu evaluasi pertama";
static char g_gate_conns[512] = "";

static const char *gate_backstop_str(int s) {
    return s == 0 ? "lokal" : (s == 1 ? "online" : "tak-pasti");
}

static bool bs_loopback_v4(const char *h) { return strcmp(h, "0100007F") == 0; }
static bool bs_loopback_v6(const char *h) {
    return strcmp(h, "00000000000000000000000001000000") == 0;
}
static bool bs_unspec(const char *h) {
    return strcmp(h, "00000000") == 0
        || strcmp(h, "00000000000000000000000000000000") == 0;
}
static long bs_rem_port(const char *rem) {
    const char *colon = strrchr(rem, ':');
    if (!colon) return -1;
    long p = 0;
    for (const char *s = colon + 1; *s; s++) {
        int v;
        if (*s >= '0' && *s <= '9') v = *s - '0';
        else if (*s >= 'A' && *s <= 'F') v = *s - 'A' + 10;
        else if (*s >= 'a' && *s <= 'f') v = *s - 'a' + 10;
        else return -1;
        p = p * 16 + v;
    }
    return p;
}
static void bs_format_endpoint(bool is_v6, const char *ip_hex, long port,
                               char *out, size_t outsz) {
    if (!is_v6 && strlen(ip_hex) == 8) {
        unsigned b[4];
        bool ok = true;
        for (int i = 0; i < 4 && ok; i++) {
            unsigned v = 0;
            for (int j = 0; j < 2; j++) {
                char c = ip_hex[i * 2 + j];
                int d;
                if (c >= '0' && c <= '9') d = c - '0';
                else if (c >= 'A' && c <= 'F') d = c - 'A' + 10;
                else if (c >= 'a' && c <= 'f') d = c - 'a' + 10;
                else { ok = false; break; }
                v = v * 16 + (unsigned)d;
            }
            b[i] = v;
        }
        if (ok) {
            snprintf(out, outsz, "%u.%u.%u.%u:%ld", b[3], b[2], b[1], b[0], port);
            return;
        }
    }
    snprintf(out, outsz, "%s:%ld", ip_hex, port);
}
static void bs_append_conn(const char *proto, const char *endpoint, const char *cls) {
    size_t cur = strlen(g_gate_conns);
    if (cur >= sizeof(g_gate_conns) - 1) return;
    char entry[128];
    snprintf(entry, sizeof(entry), "%s %s(%s)", proto, endpoint, cls);
    if (cur > 0) {
        strncat(g_gate_conns, ", ", sizeof(g_gate_conns) - cur - 1);
        cur = strlen(g_gate_conns);
    }
    strncat(g_gate_conns, entry, sizeof(g_gate_conns) - cur - 1);
}

// Hitungan satu evaluasi (direset tiap gate_evaluate_backstop).
static int bs_peer_count, bs_sync_count, bs_files_read;
static int bs_files_missing, bs_files_unreadable;

static void bs_scan_file(const char *path, bool is_tcp, bool is_v6, const char *proto) {
    FILE *f = fopen(path, "r");
    if (!f) {
        if (errno == ENOENT) bs_files_missing++;
        else bs_files_unreadable++;
        return;
    }
    bs_files_read++;
    char line[1024];
    bool first = true;
    while (fgets(line, sizeof(line), f)) {
        if (first) { first = false; continue; }
        char local[128], rem[128], st[16];
        if (sscanf(line, "%*d: %127s %127s %15s", local, rem, st) < 3) continue;
        (void)local;
        long port = bs_rem_port(rem);
        if (port < 0) continue;
        char *colon = strrchr(rem, ':');
        if (!colon) continue;
        *colon = '\0';
        const char *ip = rem;
        if (bs_unspec(ip)) continue;
        if (is_v6 ? bs_loopback_v6(ip) : bs_loopback_v4(ip)) continue;
        if (is_tcp && strcmp(st, "01") != 0) continue; // hanya ESTABLISHED
        char endpoint[96];
        bs_format_endpoint(is_v6, ip, port, endpoint, sizeof(endpoint));
        bool sync = (port == 443 || port == 80 || port == 53);
        if (sync) {
            bs_sync_count++;
            bs_append_conn(proto, endpoint, "sync");
            continue;
        }
        bs_peer_count++;
        bs_append_conn(proto, endpoint, "peer");
        if (bs_peer_count == 1) {
            snprintf(g_gate_reason, sizeof(g_gate_reason), "peer: %s %s", proto, endpoint);
        }
    }
    fclose(f);
}

static void gate_evaluate_backstop() {
    bs_peer_count = bs_sync_count = bs_files_read = 0;
    bs_files_missing = bs_files_unreadable = 0;
    g_gate_conns[0] = '\0';
    bs_scan_file("/proc/self/net/tcp", true, false, "TCP");
    bs_scan_file("/proc/self/net/tcp6", true, true, "TCP6");
    bs_scan_file("/proc/self/net/udp", false, false, "UDP");
    bs_scan_file("/proc/self/net/udp6", false, true, "UDP6");
    if (bs_peer_count > 0) {
        g_gate_state = 1;
    } else if (bs_files_unreadable > 0 || bs_files_read == 0) {
        g_gate_state = 2;
        if (bs_files_read == 0)
            snprintf(g_gate_reason, sizeof(g_gate_reason),
                     "tidak ada file net terbaca (hilang:%d tak-terbaca:%d)",
                     bs_files_missing, bs_files_unreadable);
        else
            snprintf(g_gate_reason, sizeof(g_gate_reason),
                     "file net tak-terbaca: %d (baca:%d hilang:%d)",
                     bs_files_unreadable, bs_files_read, bs_files_missing);
    } else {
        g_gate_state = 0;
        snprintf(g_gate_reason, sizeof(g_gate_reason),
                 "tanpa koneksi peer (sync diabaikan: %d)", bs_sync_count);
    }
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

// local = hasil gate_is_local_session() dari gate.cpp. Bila false
// (online ATAU tak-pasti), semua permintaan ON diabaikan total.
static void apply_features(const char *cmd, bool local) {
    for (int i = 0; i < BS_FEATURE_COUNT; i++) {
        bool want = json_true(cmd, g_feature_keys[i]);
        if (!local) want = false; // gate tertutup -> paksa OFF
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

static void write_status(int tick) {
    char feat[1024];
    build_features_str(feat, sizeof(feat));
    char st[2400];
    snprintf(st, sizeof(st),
             "{\"injected\":true,\"lib_found\":%s,\"lib_base\":\"0x%zx\","
             "\"lib_size\":%zu,\"lib_size_src\":\"%s\",\"tick\":%d,"
             "\"gate\":\"%s\",\"gate_reason\":\"%s\",\"gate_conns\":\"%s\","
             "\"features\":\"%s\","
             "\"note\":\"v1.1 eksperimental - menunggu uji HP + riset offset\"}\n",
             g_lib_found ? "true" : "false",
             g_lib_base, g_lib_size, g_lib_size_src, tick,
             gate_backstop_str(g_gate_state), g_gate_reason, g_gate_conns, feat);
    wfile(STATUS_PATH, st);
}

static void *payload_thread(void *arg) {
    (void)arg;
    char cmd[2048];
    for (int tick = 0; ; tick++) {
        // Diet v1.1: evaluasi gate backstop + tulis status tiap 3 detik
        // (dulu tiap detik). Baca cmd tetap tiap siklus — toggle merespons
        // dalam maks ~3 detik, beban proses game jauh berkurang.
        gate_evaluate_backstop();
        if (read_file(CMD_PATH, cmd, sizeof(cmd))) {
            apply_features(cmd, g_gate_state == 0);
        }
        write_status(tick);
        sleep(3);
    }
    return NULL;
}

__attribute__((constructor))
static void bs_init(void) {
    wfile(BOOT_PATH, "{\"stage\":\"bs_loaded\"}\n");
    LOGI("payload BombSquad jalan (pid=%d)", (int)getpid());
    find_libmain();
    LOGI("libmain.so: %s base=0x%zx size=%zu (src=%s)",
         g_lib_found ? "ketemu" : "TIDAK ketemu",
         g_lib_base, g_lib_size, g_lib_size_src);
    // g_gate_state sudah default tak-pasti + reason "menunggu evaluasi".
    write_status(0); // status awal: tak-pasti sampai gate pertama jalan
    pthread_t t;
    if (pthread_create(&t, NULL, payload_thread, NULL) == 0)
        pthread_detach(t);
}
