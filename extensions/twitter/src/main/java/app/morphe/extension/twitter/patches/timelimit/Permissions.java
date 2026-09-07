package app.morphe.extension.twitter.patches.timelimit;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

/** The two special-access grants the limit needs, and the settings pages that grant them. */
public final class Permissions {
    private Permissions() {}

    public static boolean usageAccess(Context c) {
        return UsageStatsReader.hasPermission(c);
    }

    public static boolean allFiles(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true;
        try {
            return Environment.isExternalStorageManager();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean allGranted(Context c) {
        return usageAccess(c) && allFiles(c);
    }

    public static Intent usageAccessIntent(Context c) {
        return new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    public static Intent allFilesIntent(Context c) {
        Intent i = new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
                Uri.parse("package:" + c.getPackageName()));
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
