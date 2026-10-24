// Hozin BombSquad Mod — payload PROBE minimal (diagnostik inject).
//
// Constructor: tulis breadcrumb + status JSON, spawn thread yang mem-poll
// dan menulis ulang status (bukti "masih hidup").
// TANPA pattern scan, TANPA hook, TANPA gate.
// Tujuan isolasi:
//   probe JALAN + payload utama tidak -> masalah di init payload utama.
//   probe juga TIDAK jalan -> masalah di mekanisme dlopen / injector.
//
// File ditulis sebagai UID proses target (game). Aplikasi (root) membuat
// file-file ini lebih dulu dengan mode 666 agar bisa ditulis UID game.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <android/log.h>

#define TAG "BSMOD-PROBE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define PROBE_BOOT   "/data/local/tmp/bs_mod_probe_boot.json"
#define PROBE_STATUS "/data/local/tmp/bs_mod_probe.json"

static void wfile(const char *path, const char *data) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) return;
    ssize_t n = (ssize_t)strlen(data);
    ssize_t w = write(fd, data, (size_t)n);
    (void)w; // diagnostik saja; abaikan hasil parsial
    close(fd);
}

static void *probe_thread(void *arg) {
    (void)arg;
    for (int i = 0; ; i++) {
        char st[160];
        // Bukti thread masih hidup di dalam proses target.
        snprintf(st, sizeof(st),
                 "{\"injected\":true,\"probe\":true,\"tick\":%d}\n", i);
        wfile(PROBE_STATUS, st);
        sleep(2);
    }
    return NULL;
}

__attribute__((constructor))
static void probe_init(void) {
    // Breadcrumb PERTAMA — sebelum apa pun. Kalau file ini tidak muncul,
    // constructor tidak pernah dieksekusi (dlopen gagal / crash loader).
    wfile(PROBE_BOOT, "{\"stage\":\"probe_loaded\"}\n");
    LOGI("probe constructor jalan (pid=%d)", (int)getpid());
    wfile(PROBE_STATUS, "{\"injected\":true,\"probe\":true,\"tick\":0}\n");
    pthread_t t;
    if (pthread_create(&t, NULL, probe_thread, NULL) == 0)
        pthread_detach(t);
}
