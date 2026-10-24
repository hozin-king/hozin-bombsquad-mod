#pragma once
// Hozin BombSquad Mod v1.0 — registry slot pola libmain.so.
//
// STATUS SEMUA SLOT DI v1.0: PATTERN_UNRESEARCHED.
// Alasan jujur: libmain.so (Ballistica, 28,7 MB) stripped tanpa simbol
// debug; REA menyatakan riset offset runtime di HP root masih dibutuhkan.
// TIDAK ADA byte pattern yang dikarang di file ini — mengarang pola sama
// saja dengan toggle palsu: scan akan "cocok" di tempat yang salah dan
// merusak memori game.
//
// Cara riset lanjutan mengisi slot ini (pola HCR):
//   1. Di HP root, jalankan game + payload, cari nilai runtime yang
//      relevan (mis. gravitasi arena, radius ledakan) lewat pemindaian
//      memori / analisis instruksi di sekitarnya.
//   2. Turunkan byte pattern AArch64 yang UNIK (tepat 1 hit di libmain.so
//      build yang ditarget) + mask wildcard untuk immediate yang boleh
//      berubah antar build.
//   3. Ubah status slot jadi PATTERN_FOUND, isi bytes/mask/delta, catat
//      build game yang diverifikasi di README, uji di HP.
//   4. Pattern-scan (bukan offset hardcode) agar tahan update game kecil.
//
// Indeks fitur harus sama dengan daftar KEYS di OverlayService dan
// g_feature_keys di payload.cpp.
#define BS_FEATURE_COUNT 13
#define BS_F_GOD_MODE            0
#define BS_F_SPEED               1
#define BS_F_GRAV_MOON           2
#define BS_F_GRAV_ZERO           3
#define BS_F_GRAV_HEAVY          4
#define BS_F_SCALE_GIANT         5
#define BS_F_SCALE_MINI          6
#define BS_F_BLAST_BIG           7
#define BS_F_PUNCH_SUPER         8
#define BS_F_TIMESCALE_SLOW      9
#define BS_F_BOT_DUMB            10
#define BS_F_BOT_FROZEN          11
#define BS_F_BOMB_TELEPORT_STICKY 12

// Status riset per slot.
#define PATTERN_UNRESEARCHED 0  // belum ada pola — fitur TIDAK diterapkan
#define PATTERN_FOUND        1  // pola terverifikasi unik di build game

// v1.0: semua slot belum diteliti. Fungsi ini satu-satunya sumber status
// pola bagi payload; riset lanjutan mengubah tabel di bawah, bukan logika
// apply di payload.cpp.
static inline int bs_pattern_status(int feature_index) {
    (void)feature_index;
    return PATTERN_UNRESEARCHED;
}

// Catatan riset per fitur (target memori yang akan dicari; BUKAN alamat):
//  god_mode:            kondisi "pemain mati" / penghitung damage di simulasi
//                       arena lokal (bascenev1lib/game/* di sumber Python).
//  speed:               pengali kecepatan gerak pemain (skala velocity).
//  grav_moon/zero/heavy: konstanta gravitasi arena (float; bulan ~1/6,
//                       nol = 0, berat = kelipatan gravitasi normal).
//  scale_giant/mini:    skala model/hitbox aktor pemain.
//  blast_big:           radius ledakan bom (float di logika bom).
//  punch_super:         gaya pukul (impuls saat pukulan mendarat).
//  timescale_slow:      skala dt update arena (slow motion).
//  bot_dumb/frozen:     logika AI bot (interval ambil keputusan / update
//                       bot dihentikan).
//  bomb_teleport_sticky: daftar aktor scene + objek bom yang sedang
//                       dilempar (posisi, kecepatan, tipe bom) — lihat
//                       bomb_teleport_tick() di payload.cpp.
