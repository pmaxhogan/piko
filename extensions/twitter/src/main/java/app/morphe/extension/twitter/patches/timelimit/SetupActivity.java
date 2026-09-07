package app.morphe.extension.twitter.patches.timelimit;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The permission gate. X does nothing until both special-access grants are
 * in place; each button deep-links to the exact system toggle. Once granted the
 * app relaunches on its own.
 */
public final class SetupActivity extends Activity {
    private Button usage, files;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.styleWindow(this);
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, "Set up the daily limit", 28, Ui.TEXT, true));
        col.addView(Ui.text(this, "Vantage X enforces a daily time limit that survives reinstalling the app. "
                + "It needs two one-time permissions to keep the count honest. Grant both, then come back.",
                16, Ui.MUTED, false), Ui.params(this, 12));

        usage = Ui.button(this, "1. Usage access", true);
        usage.setOnClickListener(v -> open(Permissions.usageAccessIntent(this)));
        col.addView(usage, Ui.params(this, 28));
        col.addView(Ui.text(this, "Lets the app read how long it has been open today from the system, "
                + "which keeps counting across force-close, reboot and reinstall.", 13, Ui.MUTED, false), Ui.params(this, 6));

        files = Ui.button(this, "2. All files access", true);
        files.setOnClickListener(v -> open(Permissions.allFilesIntent(this)));
        col.addView(files, Ui.params(this, 20));
        col.addView(Ui.text(this, "Keeps redundant copies of your limit in shared storage so clearing data "
                + "or reinstalling does not reset the count.", 13, Ui.MUTED, false), Ui.params(this, 6));

        status = Ui.text(this, "", 14, Ui.ACCENT, true);
        col.addView(status, Ui.params(this, 28));
        setContentView(Ui.scroll(this, col));
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (Throwable t) {
            status.setText("Could not open that settings page: " + t.getMessage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean u = Permissions.usageAccess(this), f = Permissions.allFiles(this);
        usage.setText((u ? "✓ " : "1. ") + "Usage access");
        files.setText((f ? "✓ " : "2. ") + "All files access");
        Ui.setEnabledLook(usage, !u);
        Ui.setEnabledLook(files, !f);
        if (u && f) {
            status.setText("All set. Opening X...");
            TimeLimit t = TimeLimit.get();
            if (t != null) t.relaunchApp(this);
            else finish();
        } else {
            int left = (u ? 0 : 1) + (f ? 0 : 1);
            status.setText(left + (left == 1 ? " permission" : " permissions") + " still needed");
        }
    }
}
