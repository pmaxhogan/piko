package app.morphe.extension.twitter.patches.timelimit;

import android.content.Context;
import android.content.Intent;
import android.preference.Preference;
import android.preference.PreferenceScreen;

/** Adds the "Daily time limit" row at the top of the Piko settings screen. */
public final class SettingsEntry {
    private SettingsEntry() {}

    public static void addTo(Context context, PreferenceScreen screen) {
        if (TimeLimit.get() == null) return;
        Preference p = new Preference(context);
        p.setKey("pref_vantage_time_limit");
        p.setTitle("Daily time limit");
        p.setSummary("Time spent today, usage history, and your limit");
        p.setOrder(-1000);
        p.setOnPreferenceClickListener(pref -> {
            context.startActivity(new Intent(context, LimitConfigActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        });
        screen.addPreference(p);
    }
}
