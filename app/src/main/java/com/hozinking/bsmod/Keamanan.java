package com.hozinking.bsmod;

/**
 * Keamanan.java — PINTU UTAMA boolean gate BombSquad Mod (sejak v1.1).
 *
 * LOKASI KEAMANAN MOD INI: file ini. Kalau mencari "keamanan" / "pintu
 * utama boolean" mod ini, INILAH tempatnya — gaya gate PvP mod Billiard:
 * satu boolean simpel, tanpa logika tersembunyi di file lain.
 *
 *   Keamanan.isLocalSession(gamePid) == true
 *       -> sesi lokal murni (solo vs bot / multiplayer lokal satu
 *          perangkat): toggle BOLEH dikirim ke payload.
 *   == false
 *       -> online ATAU tak-pasti: toggle TIDAK BOLEH dikirim, paksa OFF.
 *
 * Cara evaluasi (via root, satu panggilan su): baca network state
 * proses game (/proc/<pid>/net/tcp, tcp6, udp, udp6) lalu pilah:
 *   - SINKRONISASI (diabaikan, TIDAK memaksa online): TCP ESTABLISHED
 *     ke port 443 (HTTPS master server / layanan Google), TCP port 80
 *     (HTTP iklan/konfigurasi), dan DNS (port 53, TCP maupun UDP).
 *     Alasan: trafik party game BombSquad berjalan lewat UDP peer,
 *     bukan lewat port-port ini; sinkronisasi akun yang nyangkut di
 *     menu utama tidak boleh mematikan mod lokal.
 *   - PEER/GAME (memaksa online): TCP ESTABLISHED ke port lain, dan
 *     soket UDP yang terhubung (alamat remote terisi) ke port selain
 *     53/443.
 *   - Diabaikan: loopback, soket UDP tak terhubung (remote unspecified),
 *     dan soket TCP non-ESTABLISHED (LISTEN, TIME_WAIT, dll.).
 *   - Fail-closed: game tidak jalan, PID tak ketemu, atau tidak ada
 *     data net yang terbaca sama sekali -> tak-pasti -> false.
 *     File net yang tidak ada (mis. tcp6 di kernel tanpa IPv6) dilewati,
 *     bukan error — tanpa stack IPv6 tidak ada koneksi IPv6 yang lolos.
 *
 * YANG WAJIB MEMAKAI KELAS INI (v1.1):
 *   - OverlayService.pushCmd(): SEBELUM menulis cmd.json apa pun yang
 *     menyalakan toggle, konsultasi dulu ke sini; bila false, perintah
 *     TIDAK dikirim dan switch dipaksa OFF.
 *   - MainActivity (setelah inject): keputusan toast/status gate dari
 *     sini, bukan dari status payload semata.
 * Payload di dalam proses game TETAP menyimpan backstop fail-closed
 * (payload.cpp, inline, tiap 3 detik) sebagai jaring pengaman kedua —
 * tapi backstop BUKAN pintu utama. Satu pintu utama saja: kelas ini.
 *
 * Diet v1.1: evaluasi ini SATU panggilan su per cek (4 file dibaca
 * sekaligus dengan penanda seksi), tanpa akumulasi: hasil berupa
 * hitungan + alasan singkat, bukan daftar koneksi tanpa batas.
 */
public final class Keamanan {
    private Keamanan() {}

    /** Hasil evaluasi gate: boolean + alasan singkat untuk UI/log. */
    public static final class Hasil {
        /** true HANYA bila sesi lokal murni. */
        public final boolean lokal;
        /** Alasan singkat, mis. "lokal (sync diabaikan: 2)" atau
         *  "online: peer UDP 1.2.3.4:12345" atau "tak-pasti: ...". */
        public final String alasan;
        public final int peer;
        public final int sync;

        Hasil(boolean lokal, String alasan, int peer, int sync) {
            this.lokal = lokal;
            this.alasan = alasan;
            this.peer = peer;
            this.sync = sync;
        }
    }

    /** PINTU UTAMA boolean. gamePid dari RootUtil.pidOf(package). */
    public static boolean isLocalSession(int gamePid) {
        return cekSesi(gamePid).lokal;
    }

    /** Evaluasi lengkap untuk package game (pidOf + cekSesi). */
    public static Hasil cekSesiPaket(String pkg) {
        int pid = RootUtil.pidOf(pkg);
        if (pid <= 0) {
            return new Hasil(false, "tak-pasti: game tidak jalan", 0, 0);
        }
        return cekSesi(pid);
    }

    /** Evaluasi satu kali untuk PID game. */
    public static Hasil cekSesi(int gamePid) {
        if (gamePid <= 0) {
            return new Hasil(false, "tak-pasti: PID tidak valid", 0, 0);
        }
        // Satu panggilan su: 4 file sekaligus, dipisah penanda seksi.
        // Baris error cat (file tak ada) ikut tertampung tapi gagal parse
        // sehingga otomatis dilewati — sama seperti file ENOENT di backstop.
        String cmd = "echo ==TCP==; cat /proc/" + gamePid + "/net/tcp 2>/dev/null; "
                + "echo ==TCP6==; cat /proc/" + gamePid + "/net/tcp6 2>/dev/null; "
                + "echo ==UDP==; cat /proc/" + gamePid + "/net/udp 2>/dev/null; "
                + "echo ==UDP6==; cat /proc/" + gamePid + "/net/udp6 2>/dev/null";
        RootUtil.Result r = RootUtil.su(cmd);
        if (r.out == null || r.out.isEmpty()) {
            return new Hasil(false, "tak-pasti: data net tidak terbaca", 0, 0);
        }

        boolean isTcp = true;
        boolean dataDilihat = false;
        int peer = 0;
        int sync = 0;
        String peerPertama = null;

        for (String line : r.out.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.equals("==TCP==") || t.equals("==TCP6==")) { isTcp = true; continue; }
            if (t.equals("==UDP==") || t.equals("==UDP6==")) { isTcp = false; continue; }
            // Format: sl local_address rem_address st ...
            String[] tok = t.split("\\s+");
            if (tok.length < 4) continue;
            String rem = tok[2];
            String st = tok[3];
            int colon = rem.lastIndexOf(':');
            if (colon < 0) continue; // header / baris error cat -> lewati
            String ip = rem.substring(0, colon);
            long port = parseHexPort(rem.substring(colon + 1));
            if (port < 0) continue;
            if (isUnspec(ip) || isLoopback(ip)) continue;
            if (isTcp && !"01".equals(st)) continue; // hanya ESTABLISHED
            dataDilihat = true;
            if (port == 443 || port == 80 || port == 53) {
                sync++; // sinkronisasi akun / DNS -> diabaikan
                continue;
            }
            peer++;
            if (peerPertama == null) {
                peerPertama = (isTcp ? "TCP " : "UDP ") + formatIp(ip) + ":" + port;
            }
        }

        if (peer > 0) {
            return new Hasil(false, "online: peer " + peerPertama, peer, sync);
        }
        if (!dataDilihat) {
            return new Hasil(false, "tak-pasti: tidak ada data net terbaca", 0, sync);
        }
        return new Hasil(true, "lokal (sync diabaikan: " + sync + ")", 0, sync);
    }

    private static boolean isUnspec(String ip) {
        return ip.equals("00000000")
                || ip.equals("00000000000000000000000000000000");
    }

    private static boolean isLoopback(String ip) {
        return ip.equals("0100007F")
                || ip.equals("00000000000000000000000001000000");
    }

    /** Port hex -> long, -1 bila gagal. */
    private static long parseHexPort(String hex) {
        if (hex.isEmpty()) return -1;
        long p = 0;
        for (int i = 0; i < hex.length(); i++) {
            int d = Character.digit(hex.charAt(i), 16);
            if (d < 0) return -1;
            p = p * 16 + d;
        }
        return p;
    }

    /** IPv4 hex little-endian -> dotted decimal; IPv6 dibiarkan hex. */
    private static String formatIp(String ip) {
        if (ip.length() == 8) {
            try {
                int[] b = new int[4];
                for (int i = 0; i < 4; i++) {
                    b[i] = Integer.parseInt(ip.substring(i * 2, i * 2 + 2), 16);
                }
                return b[3] + "." + b[2] + "." + b[1] + "." + b[0];
            } catch (Exception ignored) {
                return ip;
            }
        }
        return ip;
    }
}
