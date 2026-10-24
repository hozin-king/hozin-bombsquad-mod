// Hozin BombSquad Mod — injector native (ptrace, AArch64 Android).
//
// Cara pakai:  injector <pid> <path_payload.so>
//   1. PTRACE_ATTACH ke pid target.
//   2. Cari base libc.so di target & di diri sendiri -> hitung alamat
//      dlopen di target (offset dalam libc konstan).
//   3. Tulis path payload ke stack target, panggil dlopen(path, RTLD_NOW)
//      secara remote (x0=path, x1=2, pc=dlopen, lr=0 -> SIGSEGV).
//   4. Baca x0 (handle), kembalikan register, DETACH.
//
// Dijalankan sebagai root:  su -c "libinjector.so <pid> <payload>"
// Dibangun sebagai PIE executable, dikemas sebagai libinjector.so di APK
// lalu dieksekusi dari nativeLibraryDir (direktori itu boleh di-exec).
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <dlfcn.h>
#include <errno.h>
#include <signal.h>
#include <sys/ptrace.h>
#include <sys/wait.h>

#ifdef __aarch64__
#include <sys/user.h> // struct user_pt_regs
#include <sys/uio.h>  // struct iovec
#include <elf.h>      // NT_PRSTATUS
// Di Android arm64 tidak ada PTRACE_GETREGS/SETREGS; pakai REGSET.
static int bs_getregs(pid_t pid, struct user_pt_regs* r) {
    struct iovec io = { r, sizeof(*r) };
    return ptrace(PTRACE_GETREGSET, pid, (void*)(uintptr_t)NT_PRSTATUS, &io);
}
static int bs_setregs(pid_t pid, struct user_pt_regs* r) {
    struct iovec io = { r, sizeof(*r) };
    return ptrace(PTRACE_SETREGSET, pid, (void*)(uintptr_t)NT_PRSTATUS, &io);
}
#else
// Shim agar bisa syntax-check di host x86_64 (tidak dipakai saat build NDK)
struct user_pt_regs { uint64_t regs[31]; uint64_t sp; uint64_t pc; uint64_t pstate; };
#define PTRACE_GETREGS 12
#define PTRACE_SETREGS 13
static int bs_getregs(pid_t pid, struct user_pt_regs* r) { return ptrace(PTRACE_GETREGS, pid, 0, r); }
static int bs_setregs(pid_t pid, struct user_pt_regs* r) { return ptrace(PTRACE_SETREGS, pid, 0, r); }
#endif

#define LOGI(...) do { printf("[bs-injector] "); printf(__VA_ARGS__); printf("\n"); fflush(stdout); } while (0)
#define LOGE(...) do { printf("[bs-injector] ERROR: "); printf(__VA_ARGS__); printf("\n"); fflush(stdout); } while (0)

#define INJECT_RESULT_PATH "/data/local/tmp/bs_mod_inject.json"

// Hasil remote-dlopen untuk dibaca aplikasi (injector jalan sebagai root,
// jadi tulis file ini selalu bisa — beda dengan file yang ditulis payload
// di dalam proses game).
static void write_inject_result(const char *dlres, int sig,
                                uintptr_t handle, uintptr_t pc_after,
                                const char *dlerror) {
    // Sanitasi dlerror agar aman di JSON: tanpa '"' / karakter kontrol.
    char derr[224];
    size_t j = 0;
    if (dlerror) {
        for (size_t i = 0; dlerror[i] != '\0' && j + 1 < sizeof(derr); i++) {
            char c = dlerror[i];
            if (c == '"') c = '\'';
            else if ((unsigned char)c < 0x20) c = ' ';
            derr[j++] = c;
        }
    }
    derr[j] = '\0';
    char buf[512];
    int n = snprintf(buf, sizeof(buf),
                     "{\"dlopen\":\"%s\",\"signal\":%d,"
                     "\"handle\":\"0x%lx\",\"pc_after\":\"0x%lx\","
                     "\"dlerror\":\"%s\"}\n",
                     dlres, sig,
                     (unsigned long)handle, (unsigned long)pc_after,
                     derr);
    if (n <= 0 || (size_t)n >= sizeof(buf)) return;
    FILE *f = fopen(INJECT_RESULT_PATH, "w");
    if (!f) {
        LOGE("gagal tulis %s", INJECT_RESULT_PATH);
        return;
    }
    fwrite(buf, 1, (size_t)n, f);
    fclose(f);
}

// ---- resolusi modul & simbol via /proc/<pid>/maps ----
//
// PELAJARAN PENTING (bug nyata di lapangan): di bionic Android, dlopen()
// diimplementasikan di libdl.so, BUKAN libc.so. Menghitung offset dlopen
// relatif terhadap base libc.so = mencampur dua library berbeda -> alamat
// sampah -> SIGSEGV tepat di PC yang dipanggil. Selalu cari MODUL PEMILIK
// simbolnya dulu, lalu samakan base modul YANG SAMA di target.

// base address = start mapping PERTAMA dengan pathname persis == path.
// 0 bila tidak ketemu.
static uintptr_t mod_base(pid_t pid, const char *path) {
    char mappath[64];
    snprintf(mappath, sizeof(mappath),
             pid == 0 ? "/proc/self/maps" : "/proc/%d/maps", pid);
    FILE *f = fopen(mappath, "r");
    if (!f) return 0;
    char line[1024];
    uintptr_t base = 0;
    while (fgets(line, sizeof(line), f)) {
        uintptr_t s = 0, e = 0;
        char perms[5] = {0};
        char pbuf[512] = {0};
        int n = sscanf(line, "%lx-%lx %4s %*s %*s %*d %511s",
                       &s, &e, perms, pbuf);
        if (n < 4 || pbuf[0] == '\0') continue; // butuh pathname
        if (strcmp(pbuf, path) == 0) { base = s; break; }
    }
    fclose(f);
    return base;
}

// pathname modul yang memuat `addr` (pid==0 -> proses sendiri).
// return 1 bila ketemu (out_path terisi), 0 bila tidak.
static int mod_of_addr(pid_t pid, uintptr_t addr,
                       char *out_path, size_t out_sz) {
    char mappath[64];
    snprintf(mappath, sizeof(mappath),
             pid == 0 ? "/proc/self/maps" : "/proc/%d/maps", pid);
    FILE *f = fopen(mappath, "r");
    if (!f) return 0;
    char line[1024];
    int found = 0;
    while (fgets(line, sizeof(line), f)) {
        uintptr_t s = 0, e = 0;
        char perms[5] = {0};
        char pbuf[512] = {0};
        int n = sscanf(line, "%lx-%lx %4s %*s %*s %*d %511s",
                       &s, &e, perms, pbuf);
        if (n < 4 || pbuf[0] == '\0') continue;
        if (s <= addr && addr < e) {
            snprintf(out_path, out_sz, "%s", pbuf);
            found = 1;
            break;
        }
    }
    fclose(f);
    return found;
}

// Verifikasi: addr harus berada di mapping executable (perms ada 'x')
// milik modul `path` pada target. 1=OK, 0=gagal (jangan panggil!).
static int verify_xmap(pid_t pid, uintptr_t addr, const char *path) {
    char mappath[64];
    snprintf(mappath, sizeof(mappath), "/proc/%d/maps", pid);
    FILE *f = fopen(mappath, "r");
    if (!f) return 0;
    char line[1024];
    int ok = 0;
    while (fgets(line, sizeof(line), f)) {
        uintptr_t s = 0, e = 0;
        char perms[5] = {0};
        char pbuf[512] = {0};
        int n = sscanf(line, "%lx-%lx %4s %*s %*s %*d %511s",
                       &s, &e, perms, pbuf);
        if (n < 4 || pbuf[0] == '\0') continue;
        if (s <= addr && addr < e) {
            ok = (strchr(perms, 'x') != NULL) && (strcmp(pbuf, path) == 0);
            LOGI("verify: addr=%p -> %s %s (modul %s): %s",
                 (void *)addr, pbuf, perms, path, ok ? "OK" : "SALAH");
            break;
        }
    }
    fclose(f);
    return ok;
}

// Alamat simbol di proses target:
//   dlsym lokal -> cari modul pemiliknya -> base modul YANG SAMA di
//   target -> base_target + (local - base_self).
// return 0 bila gagal (LOGE menjelaskan kenapa).
static uintptr_t remote_sym2(pid_t pid, const char *sym, const char *fallback,
                             char *out_diag, size_t diag_sz,
                             char *out_mod, size_t mod_sz) {
    const char *used = sym;
    void *local = dlsym(RTLD_DEFAULT, used);
    if (!local && fallback) {
        used = fallback;
        local = dlsym(RTLD_DEFAULT, used);
    }
    if (!local) {
        LOGE("dlsym(%s%s%s) gagal", sym, fallback ? "/" : "",
             fallback ? fallback : "");
        return 0;
    }

    char modpath[512];
    if (!mod_of_addr(0, (uintptr_t)local, modpath, sizeof(modpath))) {
        LOGE("modul pemilik %s (%p) tak ketemu di /proc/self/maps", used, local);
        return 0;
    }
    uintptr_t self_base = mod_base(0, modpath);
    uintptr_t targ_base = mod_base(pid, modpath);
    if (!self_base || !targ_base) {
        LOGE("base %s tak ketemu (self=%p target=%p)", modpath,
             (void *)self_base, (void *)targ_base);
        return 0;
    }
    uintptr_t off = (uintptr_t)local - self_base;
    uintptr_t remote = targ_base + off;
    if (out_diag)
        snprintf(out_diag, diag_sz,
                 "sym=%s mod=%s self_base=%p targ_base=%p off=0x%lx remote=%p",
                 used, modpath, (void *)self_base, (void *)targ_base,
                 (unsigned long)off, (void *)remote);
    if (out_mod)
        snprintf(out_mod, mod_sz, "%s", modpath);
    return remote;
}

// Wrapper khusus dlopen (fallback __loader_dlopen).
static uintptr_t remote_sym(pid_t pid, char *out_diag, size_t diag_sz,
                            char *out_mod, size_t mod_sz) {
    return remote_sym2(pid, "dlopen", "__loader_dlopen",
                       out_diag, diag_sz, out_mod, mod_sz);
}

static int poke_text(pid_t pid, uintptr_t addr, const void* data, size_t len) {
    const uint8_t* p = (const uint8_t*)data;
    for (size_t i = 0; i < len; i += 8) {
        uint64_t word = 0;
        size_t chunk = len - i < 8 ? len - i : 8;
        memcpy(&word, p + i, chunk);
        if (ptrace(PTRACE_POKETEXT, pid, (void*)(addr + i), (void*)(uintptr_t)word) != 0) {
            LOGE("POKETEXT gagal @ %p: %s", (void*)(addr + i), strerror(errno));
            return -1;
        }
    }
    return 0;
}

// Baca string C (NUL-terminated) dari memori target via PTRACE_PEEKDATA.
static void read_remote_str(pid_t pid, uintptr_t addr, char *out, size_t out_sz) {
    if (out_sz == 0) return;
    size_t i = 0;
    while (i + 1 < out_sz) {
        errno = 0;
        long w = ptrace(PTRACE_PEEKDATA, pid, (void *)(addr + i), 0);
        if (w == -1 && errno != 0) break;
        for (int b = 0; b < 8 && i + 1 < out_sz; b++) {
            char c = (char)((w >> (b * 8)) & 0xFF);
            out[i++] = c;
            if (c == '\0') return;
        }
    }
    out[out_sz - 1] = '\0';
}

// Panggil fungsi remote tanpa argumen; return nilai x0.
// Hanya untuk diagnostik (dlerror): bila crash -> *crashed=1, return 0.
// Asumsi: thread target sedang STOPPED pasca-trap dan sp masih valid.
static uintptr_t remote_call0(pid_t pid, uintptr_t func, int *crashed) {
    if (crashed) *crashed = 1;
    struct user_pt_regs cur;
    if (bs_getregs(pid, &cur) != 0) return 0;
    cur.regs[0] = 0;
    cur.regs[30] = 0; // lr=0 -> ret memicu SIGSEGV trap
    cur.pc = func;
    if (bs_setregs(pid, &cur) != 0) return 0;
    if (ptrace(PTRACE_CONT, pid, 0, 0) != 0) return 0;
    int status = 0;
    waitpid(pid, &status, 0);
    if (!WIFSTOPPED(status) || WSTOPSIG(status) != SIGSEGV) return 0;
    struct user_pt_regs after;
    if (bs_getregs(pid, &after) != 0) return 0;
    if (after.pc != 0) return 0; // crash di dalam fungsi
    if (crashed) *crashed = 0;
    return after.regs[0];
}

int main(int argc, char** argv) {
    if (argc != 3) {
        printf("pakai: %s <pid> <payload.so>\n", argv[0]);
        return 2;
    }
    pid_t pid = (pid_t)atoi(argv[1]);
    const char* payload = argv[2];
    if (pid <= 1) { LOGE("pid tidak valid"); return 2; }

    // --- alamat dlopen di target (SEBELUM attach; gagal -> target tak disentuh) ---
    char rdiag[512] = {0};
    char rmod[512] = {0};
    uintptr_t remote_dlopen = remote_sym(pid, rdiag, sizeof(rdiag),
                                         rmod, sizeof(rmod));
    if (!remote_dlopen) { LOGE("resolusi dlopen gagal — batal"); return 1; }
    LOGI("dlopen target: %s", rdiag);
    if (!verify_xmap(pid, remote_dlopen, rmod)) {
        LOGE("alamat dlopen tidak di mapping x-perm %s — batal", rmod);
        return 1;
    }

    // --- alamat dlerror di target (diagnostik saja; gagal -> lanjut tanpanya) ---
    char ddiag[512] = {0};
    char dmod[512] = {0};
    uintptr_t remote_dlerror = remote_sym2(pid, "dlerror", NULL,
                                           ddiag, sizeof(ddiag),
                                           dmod, sizeof(dmod));
    if (remote_dlerror) {
        LOGI("dlerror target: %s", ddiag);
        if (!verify_xmap(pid, remote_dlerror, dmod)) {
            LOGE("alamat dlerror tidak valid — diagnostik dlerror dimatikan");
            remote_dlerror = 0;
        }
    } else {
        LOGE("resolusi dlerror gagal — lanjut tanpa diagnostik dlerror");
    }

    // --- attach ---
    if (ptrace(PTRACE_ATTACH, pid, 0, 0) != 0) { LOGE("ATTACH gagal: %s", strerror(errno)); return 1; }
    int status = 0;
    waitpid(pid, &status, 0);

    struct user_pt_regs regs, saved;
    if (bs_getregs(pid, &saved) != 0) { LOGE("GETREGS gagal"); goto detach; }
    regs = saved;

    // --- tulis path payload ke stack target ---
    size_t plen = strlen(payload) + 1;
    uintptr_t str_addr = (saved.sp - 0x1000) & ~ (uintptr_t)0xF;
    if (poke_text(pid, str_addr, payload, plen) != 0) goto restore;

    // --- panggil dlopen(path, RTLD_NOW=2) ---
    regs.regs[0] = str_addr;
    regs.regs[1] = 2;
    regs.regs[30] = 0;              // lr = 0 -> ret memicu SIGSEGV
    regs.pc = remote_dlopen;
    regs.sp = (str_addr - 0x100) & ~(uintptr_t)0xF;
    if (bs_setregs(pid, &regs) != 0) { LOGE("SETREGS gagal"); goto restore; }
    if (ptrace(PTRACE_CONT, pid, 0, 0) != 0) { LOGE("CONT gagal"); goto restore; }
    waitpid(pid, &status, 0);

    // Analisis hasil remote-dlopen. Kasus NORMAL: dlopen return -> ret ke
    // lr=0 -> SIGSEGV dengan pc=0 (trap yang memang diharapkan).
    // Kalau pc != 0 -> target CRASH di DALAM dlopen (mis. constructor
    // payload atau static-init crash) — bukan trap normal.
    const char *dlres = "unknown";
    char dlerrbuf[256] = {0}; // pesan remote dlerror() bila dlopen NULL
    int sig = 0;
    uintptr_t handle = 0, pc_after = 0;
    if (WIFSTOPPED(status)) {
        sig = WSTOPSIG(status);
        struct user_pt_regs after;
        if (bs_getregs(pid, &after) != 0) { LOGE("GETREGS(after) gagal"); goto restore; }
        handle = after.regs[0];
        pc_after = after.pc;
        if (sig == SIGSEGV && pc_after == 0) {
            if (handle != 0) {
                dlres = "ok";
                LOGI("dlopen OK (trap normal ret->0), handle=%p", (void *)handle);
            } else {
                dlres = "null";
                LOGE("dlopen mengembalikan NULL (payload tidak ter-load)");
                // Diagnostik penentu: tanya kenapa via remote dlerror().
                if (remote_dlerror) {
                    int crashed = 0;
                    uintptr_t errptr = remote_call0(pid, remote_dlerror, &crashed);
                    if (!crashed && errptr != 0) {
                        read_remote_str(pid, errptr, dlerrbuf, sizeof(dlerrbuf));
                        LOGE("dlerror: %s", dlerrbuf);
                    } else {
                        LOGE("dlerror: (panggil remote dlerror gagal)");
                    }
                }
            }
        } else {
            dlres = "crash";
            LOGE("target CRASH di dalam dlopen! signal=%d pc=%p",
                 sig, (void *)pc_after);
        }
    } else {
        dlres = "exited";
        LOGE("target tidak berhenti seperti harapan (status=0x%x)", status);
        goto restore;
    }
    write_inject_result(dlres, sig, handle, pc_after, dlerrbuf);

restore:
    bs_setregs(pid, &saved);
detach:
    ptrace(PTRACE_DETACH, pid, 0, 0);
    LOGI("detach selesai");
    return 0;
}
