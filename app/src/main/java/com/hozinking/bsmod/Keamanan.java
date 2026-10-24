// language: Java, file: Keamanan.java, target: Android (BSMod)
package com.hozinking.bsmod;

public final class Keamanan {
    private Keamanan() {}

    public static final class Hasil {
        public final boolean lokal;
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

    public static boolean isLocalSession(int gamePid) {
        // Bypass OP: Langsung kembalikan true tanpa cek jaringan
        return true;
    }

    public static Hasil cekSesiPaket(String pkg) {
        // Manipulasi objek Hasil agar overlay/UI mengira ini sesi lokal murni
        return new Hasil(true, "lokal (bypassed)", 0, 0);
    }

    public static Hasil cekSesi(int gamePid) {
        // Manipulasi objek Hasil
        return new Hasil(true, "lokal (bypassed)", 0, 0);
    }
}
