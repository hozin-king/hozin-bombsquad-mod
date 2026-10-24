# Hozin BombSquad Mod 💣🧪

Aplikasi Android **root** (pola HCR) untuk mod menu **BombSquad 1.8.4**
(`net.froemling.bombsquad`, engine Ballistica C++ `libmain.so` + logika
Python lokal).

> ⚠️ **v1.1 = EKSPERIMENTAL.** Offset/pattern runtime `libmain.so` BELUM
> terverifikasi — butuh riset offset di HP root. Yang v1.1 sudah lakukan
> nyata: inject + handshake status + **gate sesi (pintu utama
> Keamanan.java)** + diagnostik gate + diet memori.
> Toggle tidak akan aktif sampai pola fitur ditemukan di riset lanjutan.
> Jangan klaim fitur sudah jalan sebelum uji HP.
>
> **Yang berubah di v1.1 (dari v1.0):** pintu utama gate pindah ke
> `Keamanan.java` (boolean simpel gaya gate PvP Billiard); gate dipilah
> akun vs party (sinkronisasi akun tidak lagi menutup gate di menu
> utama); `lib_size` diperbaiki (v1.0: 0); polling dilonggarkan +
> log dibatasi (diet memori, keluhan lag v1.0).

## Cara kerja

1. Aplikasi (butuh root/KernelSU) mencari PID game via `pidof`.
2. Injector native (ptrace, `libinjector.so`) memanggil `dlopen` jarak
   jauh untuk memuat `libpayload.so` ke proses game — **tanpa resign APK
   game** (signature resmi Eric Froemling tetap utuh).
3. Payload mencari `libmain.so` di proses game, menulis status ke
   `/data/local/tmp/bs_mod_status.json`, dan membaca toggle dari
   `/data/local/tmp/bs_mod_cmd.json` tiap detik.
4. Overlay: logo "B" mengambang (draggable) → tap → panel menu.
   Auto-inject saat game dibuka (WatchService, polling tiap 2 detik).

## Gate keamanan (SATU pintu utama boolean)

**PINTU UTAMA: `app/src/main/java/com/hozinking/bsmod/Keamanan.java`**
— kalau mencari "keamanan" mod ini, buka file itu. Satu boolean simpel
gaya gate PvP mod Billiard:

```java
Keamanan.isLocalSession(gamePid) // true HANYA bila sesi lokal murni
```

Evaluasi via root dari network state proses game
(`/proc/<pid>/net/tcp, tcp6, udp, udp6`), satu panggilan `su` per cek:

- Koneksi **sinkronisasi akun diabaikan** (TIDAK memaksa online): TCP
  ESTABLISHED ke port 443 (HTTPS master server / layanan Google), TCP
  port 80 (HTTP iklan/konfigurasi), DNS port 53. Alasan: trafik party
  game BombSquad berjalan lewat UDP peer, bukan port-port ini — jadi
  sinkronisasi akun yang nyangkut di menu utama tidak boleh mematikan
  mod lokal (ini perbaikan atas gate v1.0 yang membaca "tak-pasti" di
  menu utama).
- Koneksi **peer/game memaksa online**: TCP ESTABLISHED ke port lain,
  dan soket UDP terhubung ke port selain 53/443 → semua fitur OFF.
- **Fail-closed**: game tidak jalan, PID tak ketemu, atau tidak ada data
  net terbaca → tak-pasti → false → fitur OFF. File net yang tidak ada
  (mis. tcp6 di kernel tanpa IPv6) dilewati, bukan error.

**Yang WAJIB konsultasi ke Keamanan.java:** `OverlayService.pushCmd()`
SEBELUM menulis perintah toggle apa pun ke `cmd.json`, dan `MainActivity`
untuk keputusan toast/status gate. Bila boolean false, perintah TIDAK
dikirim dan switch dipaksa OFF.

**Backstop (jaring kedua, BUKAN pintu utama):** payload di dalam proses
game (`payload.cpp`, inline) memakai logika klasifikasi yang SAMA,
dievaluasi tiap 3 detik. Bila backstop menutup, toggle dipaksa OFF meski
Keamanan berkata lokal. Tidak ada dua "gate utama" — satu pintu utama
(Keamanan.java) + satu backstop.

**Diagnostik (v1.1):** status JSON membawa `gate_reason` (alasan
keputusan), `gate_conns` (ringkasan koneksi terlihat), `tick` (bukti
thread payload hidup), dan `lib_size` + `lib_size_src` — semuanya tampil
di UI (panel overlay + MainActivity) agar evaluasi gate bisa
diverifikasi, bukan ditebak.

## Diet memori (v1.1, keluhan lag v1.0)

- Log `MainActivity` dibatasi **200 baris terakhir** (v1.0 append tanpa
  batas — sumber pembengkakan paling jelas).
- Polling dilonggarkan ke **3 detik**: overlay, WatchService (dulu 2
  detik), dan payload (dulu tiap detik). Loop tunggu injeksi memakai
  cek 2 detik (total durasi sama).
- UI hanya di-update bila nilai bermakna berubah (tick yang naik tiap
  siklus tidak dihitung berubah).
- Tidak ada akumulasi string/list tanpa batas di service mana pun
  (WatchService, OverlayService, ModInjector) — semua buffer berbatas.

## Fitur (menu overlay, semua default OFF)

- God Mode, Speed (pengali 1.5x)
- Paket cheat aneh: gravitasi bulan / nol / berat, skala karakter
  raksasa / mini, ledakan raksasa, pukulan super, slow motion,
  bot bego, bot beku
- Bom teleport lengket: bom yang dilempar pindah ke musuh terdekat +
  paksa tipe sticky bawaan game (mekanik asli, bukan ciptaan mod)

## YANG TIDAK ADA (dan tidak akan ditambahkan)

Unlock karakter/map/mini-game, unlimited ticket/token, Pro/Gold Pass
gratis, remove_ads, cheat party publik, cheat turnamen/liga — semuanya
server-side (data akun disinkron master server, signature ed25519) /
bypass pembelian / merugikan pemain asli.

## Yang masih menunggu

1. **Uji HP v1.1**: gate membaca "lokal" di menu utama? `lib_size`
   tampil > 0 (bukan 0)? Alasan gate tampil di UI? RAM/lag membaik vs
   v1.0? (Toggle tetap OFF sampai pola riset terisi — itu benar, bukan
   bug. Uji HP v1.0 24 Okt 20:50: inject OK, overlay muncul, gate
   "tak-pasti", `lib_size: 0` — dua anomali itu yang diperbaiki v1.1.)
2. **Riset offset runtime** `libmain.so` per fitur — pattern-scan (bukan
   offset hardcode) agar tahan update game kecil. Slot + target riset
   didokumentasikan di `app/src/main/cpp/payload/patterns.h`.
   **Tidak ada byte pattern yang dikarang di v1.1** — mengarang pola
   sama saja dengan toggle palsu.

## Build & rilis

CI (`.github/workflows/build-release.yml`, repo publik = Actions gratis):
JDK 17 Temurin + Gradle 8.7 + NDK 25.2.9519653 + CMake 3.22.1 →
`assembleRelease` → Release `v1.1` + APK.

Signing rilis memakai keystore proyek yang disimpan sebagai GitHub
Secrets repo (tidak pernah di-commit):

- `KEYSTORE_BASE64` — keystore (base64)
- `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

## Struktur

```
app/                  aplikasi (Java) + overlay + info versi game
  Keamanan.java       PINTU UTAMA gate (boolean isLocalSession) — buka ini
                      dulu kalau mencari "keamanan" mod ini
app/src/main/cpp/
  injector/           injector ptrace (libinjector.so, dijalankan via su)
  payload/            payload (libpayload.so)
    patterns.h        registry slot pola — v1.1 SEMUA masih "menunggu-riset"
    payload.cpp       cari libmain (+lib_size), backstop gate inline
                      (tiap 3 detik), loop cmd, status
    payload_probe.cpp probe minimal untuk isolasi masalah inject
```

## Catatan keamanan & batas

- Hanya untuk perangkat sendiri + HP root sendiri. Jangan pakai mod di
  party publik / turnamen — gate memang dirancang mematikan fitur di
  sana, dan mencoba mengakali gate = curang lawan pemain asli.
- Repo ini TIDAK berisi APK BombSquad, hasil ekstrak game, source
  Python game, keystore, atau secret apa pun — hanya source mod.
- CI hijau hanya membuktikan **build sukses**, bukan bahwa fitur
  berfungsi. Bukti fungsi hanya dari uji HP.
