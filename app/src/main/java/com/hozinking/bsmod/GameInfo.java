package com.hozinking.bsmod;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Baca versionCode + SHA256 libmain.so dari APK game yang terinstal.
 * Dipakai untuk deteksi update: kalau versionCode / hash berubah,
 * pola di-scan ulang otomatis saat inject berikutnya.
 */
public final class GameInfo {
    public final long versionCode;
    public final String versionName;
    public final String libSha256; // "" bila lib tidak ketemu

    private GameInfo(long versionCode, String versionName, String libSha256) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.libSha256 = libSha256;
    }

    public static GameInfo read(Context ctx, String pkg) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        PackageInfo pi = pm.getPackageInfo(pkg, 0);
        // getLongVersionCode() baru ada di API 28; minSdk kita 26
        long vc = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        String vn = pi.versionName != null ? pi.versionName : "?";

        ApplicationInfo ai = pi.applicationInfo;
        List<String> apks = new ArrayList<>();
        apks.add(ai.sourceDir);
        if (ai.splitSourceDirs != null) {
            for (String s : ai.splitSourceDirs) apks.add(s);
        }

        String sha = "";
        String[] libPaths = {"lib/arm64-v8a/libmain.so", "lib/armeabi-v7a/libmain.so"};
        outer:
        for (String apk : apks) {
            ZipFile z = null;
            try {
                z = new ZipFile(apk);
                for (String lp : libPaths) {
                    ZipEntry e = z.getEntry(lp);
                    if (e == null) continue;
                    InputStream in = z.getInputStream(e);
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
                    in.close();
                    StringBuilder sb = new StringBuilder();
                    for (byte b : md.digest()) sb.append(String.format("%02x", b));
                    sha = sb.toString();
                    break outer;
                }
            } catch (Exception ignored) {
                // lanjut ke split berikutnya
            } finally {
                if (z != null) try { z.close(); } catch (Exception ignored) {}
            }
        }
        return new GameInfo(vc, vn, sha);
    }
}
