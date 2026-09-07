package app.morphe.extension.twitter.patches.timelimit;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The redundant copies of the signed record. Every copy is written on every
 * save and every copy is read on every load; the merge in {@link StateRecord}
 * makes the result at least as strict as the strictest surviving copy, so a
 * user has to find and delete ALL of them, at the same time, to lose state -
 * and even then UsageStats still knows how long the app was open today.
 *
 *  1. app SharedPreferences                          - dies with clear-data / uninstall
 *  2. /sdcard/Download/.vantage-x.dat                 - survives both (the obvious one)
 *  3. /sdcard/Pictures/.thumbnails/.thumbdata_vx.png  - survives both, is a real PNG
 *  4. /sdcard/Music/.cache/.media_index               - survives both
 *  5. /sdcard/Android/media/<pkg>/.twitter_cache      - survives clear-data
 *  6. /sdcard/DCIM/.thumbnails/.thumbindex            - survives both
 *  7. /sdcard/Movies/.thumbnails/.mtx                 - survives both
 *
 * Settings.System was tried and dropped: since Android 8 the settings provider
 * rejects any System key it does not have a validator for ("You cannot keep
 * your settings in the secure settings"), so a third-party app cannot keep an
 * arbitrary value there no matter what it is granted. The names above are
 * deliberately varied so one glob does not find them all; Android's own usage
 * statistics remain the system-side backstop for time spent regardless.
 *
 * The raw-file copies need "All files access" (MANAGE_EXTERNAL_STORAGE): with
 * scoped storage a reinstalled app is no longer the owner of the files it wrote
 * before, so MediaStore would refuse to hand them back. All-files access is
 * part of the mandatory setup gate for exactly this reason.
 */
public final class Stores {
    private static final String TAG = "VantageX/Stores";

    private Stores() {}

    public static List<BlobStore> all(Context context) {
        List<BlobStore> list = new ArrayList<>();
        list.add(new PrefsStore(context));
        File ext = Environment.getExternalStorageDirectory();
        list.add(new FileStore("download", new File(ext, "Download/.vantage-x.dat"), false));
        list.add(new FileStore("pictures-png", new File(ext, "Pictures/.thumbnails/.thumbdata_vx.png"), true));
        list.add(new FileStore("music", new File(ext, "Music/.cache/.media_index"), false));
        File[] media = context.getExternalMediaDirs();
        if (media != null && media.length > 0 && media[0] != null) {
            list.add(new FileStore("android-media", new File(media[0], ".twitter_cache"), false));
        }
        list.add(new FileStore("dcim", new File(ext, "DCIM/.thumbnails/.thumbindex"), false));
        list.add(new FileStore("movies", new File(ext, "Movies/.thumbnails/.mtx"), false));
        return list;
    }

    // ---- 1. SharedPreferences -------------------------------------------

    static final class PrefsStore implements BlobStore {
        private final SharedPreferences prefs;

        PrefsStore(Context c) {
            prefs = c.getApplicationContext().getSharedPreferences("vantage_x_timelimit", Context.MODE_PRIVATE);
        }

        @Override public String name() { return "prefs"; }

        @Override public String read() { return prefs.getString("r", null); }

        @Override public boolean write(String encoded) {
            return prefs.edit().putString("r", encoded).commit();
        }
    }

    // ---- 2-7. raw files -------------------------------------------

    static final class FileStore implements BlobStore {
        private final String name;
        private final File file;
        private final boolean png;

        FileStore(String name, File file, boolean png) {
            this.name = name;
            this.file = file;
            this.png = png;
        }

        @Override public String name() { return name; }

        @Override public String read() {
            try {
                if (!file.isFile()) return null;
                long len = file.length();
                if (len <= 0 || len > 64 * 1024) return null;
                byte[] buf = new byte[(int) len];
                try (FileInputStream in = new FileInputStream(file)) {
                    int off = 0, n;
                    while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
                }
                return png ? PngCarrier.unwrap(buf) : new String(buf, StandardCharsets.UTF_8);
            } catch (Throwable t) {
                return null;
            }
        }

        @Override public boolean write(String encoded) {
            try {
                File dir = file.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
                byte[] bytes = png ? PngCarrier.wrap(encoded) : encoded.getBytes(StandardCharsets.UTF_8);
                File tmp = new File(dir, file.getName() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(bytes);
                    out.getFD().sync();
                }
                if (!tmp.renameTo(file)) {
                    // Some filesystems refuse rename-over; fall back to a direct write.
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        out.write(bytes);
                    }
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
                return true;
            } catch (IOException | SecurityException t) {
                Log.w(TAG, name + " write failed: " + t);
                return false;
            }
        }
    }
}
