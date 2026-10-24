# Hozin BombSquad Mod 💣🧪

Aplikasi Android **root** (pola HCR) untuk mod menu **BombSquad 1.8.4**
(`net.froemling.bombsquad`, engine Ballistica C++ `libmain.so` + logika
Python lokal).

> ⚠️ **v1.0 = EKSPERIMENTAL.** Offset/pattern runtime `libmain.so` BELUM
> terverifikasi — butuh riset offset di HP root. Yang v1.0 sudah lakukan
> nyata: inject + handshake status + **gate sesi runtime (fail-closed)**.
> Toggle tidak akan aktif sampai pola fitur ditemukan di riset lanjutan.
> Jangan klaim fitur sudah jalan sebelum uji HP.

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

## Gate keamanan (satu boolean penjaga semua fitur)

`isLocalSession` di payload, dievaluasi ulang tiap detik dari koneksi
jaringan proses game sendiri (`/proc/self/net/*`):

- Ada koneksi TCP ESTABLISHED / soket UDP ke alamat remote non-loopback
  → **online** → semua fitur dipaksa OFF.
- File net tidak bisa dibaca → **tak-pasti** → OFF (fail-closed).
- Tidak ada koneksi remote → **lokal** → toggle boleh berlaku
  (tetap menunggu pola riset per fitur).

Keterbatasan jujur: koneksi akun/sinkronisasi atau iklan yang nyangkut
saat main lokal bisa membuat gate tertutup konservatif. Itu arah aman
yang benar; riset perangkat lanjutan akan memilah endpoint akun vs
endpoint party. Gate ditegakkan di payload, bukan cuma label UI — dan
overlay ikut memaksa toggle OFF saat gate menutup.

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

1. **Uji HP**: inject jalan? status payload muncul? gate membaca sesi
   dengan benar (lokal vs online)?
2. **Riset offset runtime** `libmain.so` per fitur — pattern-scan (bukan
   offset hardcode) agar tahan update game kecil. Slot + target riset
   didokumentasikan di `app/src/main/cpp/payload/patterns.h`.
   **Tidak ada byte pattern yang dikarang di v1.0** — mengarang pola
   sama saja dengan toggle palsu.

## Build & rilis

CI (`.github/workflows/build-release.yml`, repo publik = Actions gratis):
JDK 17 Temurin + Gradle 8.7 + NDK 25.2.9519653 + CMake 3.22.1 →
`assembleRelease` → Release `v1.0` + APK.

Signing rilis memakai keystore proyek yang disimpan sebagai GitHub
Secrets repo (tidak pernah di-commit):

- `KEYSTORE_BASE64` — keystore (base64)
- `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

## Struktur

```
app/                  aplikasi (Java) + overlay + info versi game
app/src/main/cpp/
  injector/           injector ptrace (libinjector.so, dijalankan via su)
  payload/            payload (libpayload.so)
    patterns.h        registry slot pola — v1.0 SEMUA masih "menunggu-riset"
    payload.cpp       cari libmain, gate sesi, loop cmd, status
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
