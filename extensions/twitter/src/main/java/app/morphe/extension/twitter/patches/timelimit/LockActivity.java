package app.morphe.extension.twitter.patches.timelimit;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shown, and re-shown, whenever the app is opened after the daily limit is
 * reached. Only "Change limit" (the config screen) and "Close" lead anywhere;
 * back closes too. When the 05:00 reset passes while it is on screen it
 * relaunches the app on its own.
 */
public final class LockActivity extends Activity {
    private TextView spent;
    private TextView countdown;
    private final Runnable refresh = this::refresh;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.styleWindow(this);
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, "Daily limit reached", 28, Ui.TEXT, true));
        col.addView(Ui.text(this, "You have used all of today's time on X. It opens again at 5:00 AM.",
                16, Ui.MUTED, false), Ui.params(this, 12));
        spent = Ui.text(this, "", 16, Ui.TEXT, false);
        col.addView(spent, Ui.params(this, 28));
        countdown = Ui.text(this, "", 34, Ui.ACCENT, true);
        col.addView(countdown, Ui.params(this, 6));
        col.addView(Ui.text(this, "until the reset", 14, Ui.MUTED, false), Ui.params(this, 2));

        Button change = Ui.button(this, "Change daily limit", false);
        change.setOnClickListener(v -> startActivity(new Intent(this, LimitConfigActivity.class)));
        col.addView(change, Ui.params(this, 40));
        Button close = Ui.button(this, "Close X", true);
        close.setOnClickListener(v -> closeApp());
        col.addView(close, Ui.params(this, 12));
        col.addView(Ui.text(this, "Increases to the limit start tomorrow. Lowering it applies right away.",
                13, Ui.MUTED, false), Ui.params(this, 24));
        setContentView(Ui.scroll(this, col));
    }

    private void refresh() {
        TimeLimit t = TimeLimit.get();
        if (t == null) return;
        TimeLimit.Snapshot s = t.snapshot();
        if (!s.locked) {
            // The day rolled over (or the limit was raised by a new day). Back to X.
            t.relaunchApp(this);
            return;
        }
        spent.setText("Used " + Ui.hms(s.spentMs) + " of your " + Ui.hm(s.limitMinutes) + " limit");
        long left = s.nextResetWallMs - s.trustedWallMs;
        countdown.setText(Ui.hms(left));
        if (s.nextDayLimitMinutes != s.limitMinutes) {
            spent.append("\nTomorrow's limit: " + Ui.hm(s.nextDayLimitMinutes));
        }
    }

    private void closeApp() {
        finishAndRemoveTask();
    }

    @Override
    public void onBackPressed() {
        closeApp();
    }

    @Override
    protected void onResume() {
        super.onResume();
        TimeLimit t = TimeLimit.get();
        if (t != null) t.addListener(refresh);
        refresh();
    }

    @Override
    protected void onPause() {
        TimeLimit t = TimeLimit.get();
        if (t != null) t.removeListener(refresh);
        super.onPause();
    }
}
