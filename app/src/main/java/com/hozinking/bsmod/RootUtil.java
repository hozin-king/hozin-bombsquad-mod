package com.hozinking.bsmod;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Helper root: jalankan perintah via su. */
public final class RootUtil {
    private RootUtil() {}

    public static class Result {
        public final String out;
        public final int code;
        Result(String out, int code) { this.out = out; this.code = code; }
    }

    /** Jalankan perintah sebagai root. Kembalikan stdout + exit code. */
    public static Result su(String cmd) {
        StringBuilder sb = new StringBuilder();
        int code = -1;
        try {
            Process p = new ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            code = p.waitFor();
            r.close();
        } catch (Exception e) {
            sb.append("EXC: ").append(e.getMessage());
        }
        return new Result(sb.toString().trim(), code);
    }

    public static boolean isRooted() {
        Result r = su("id");
        return r.code == 0 && r.out.contains("uid=0");
    }

    /** PID dari package name, atau -1 bila tidak jalan / tidak ketemu. */
    public static int pidOf(String pkg) {
        Result r = su("pidof " + pkg);
        if (r.code != 0 || r.out.isEmpty()) return -1;
        try {
            return Integer.parseInt(r.out.split("\\s+")[0]);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Tulis teks ke path via root (heredoc aman untuk quote).
     *  Mode 666: file milik root tapi bisa dibaca proses game (UID game,
     *  bukan root) — payload membaca cmd.json sebagai UID game. */
    public static boolean writeRootFile(String path, String content) {
        String esc = content.replace("'", "'\"'\"'");
        Result r = su("printf '%s' '" + esc + "' > " + path + " && chmod 666 " + path);
        return r.code == 0;
    }
}
