package app.morphe.extension.twitter.patches.timelimit;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * The one place the limit can be changed. Nothing applies until "Save limit"
 * is tapped and the confirmation accepted; backing out with a pending change
 * asks first. Live numbers refresh every second from the controller.
 */
public final class LimitConfigActivity extends Activity {
    private TextView spentText, limitText, note;
    private ProgressBar bar;
    private UsageGraphView graph;
    private NumberPicker hours, minutes;
    private Button save;
    private int maxMinutes = 120;
    private int savedMinutes = 30;
    private final Runnable refresh = this::refresh;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.styleWindow(this);
        TimeLimit t = TimeLimit.get();
        TimeLimit.Snapshot s = t == null ? null : t.snapshot();
        maxMinutes = s == null ? 120 : s.maxMinutes;
        savedMinutes = s == null ? 30 : s.configuredMinutes;

        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, "Daily time limit", 28, Ui.TEXT, true));
        spentText = Ui.text(this, "", 16, Ui.TEXT, false);
        col.addView(spentText, Ui.params(this, 16));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.getProgressDrawable().setColorFilter(Ui.ACCENT, android.graphics.PorterDuff.Mode.SRC_IN);
        col.addView(bar, Ui.params(this, 8));
        limitText = Ui.text(this, "", 14, Ui.MUTED, false);
        col.addView(limitText, Ui.params(this, 6));

        col.addView(Ui.text(this, "Last 7 days", 14, Ui.MUTED, true), Ui.params(this, 24));
        graph = new UsageGraphView(this);
        col.addView(graph, Ui.params(this, 8));

        col.addView(Ui.text(this, "New daily limit", 14, Ui.MUTED, true), Ui.params(this, 28));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        hours = picker(0, maxMinutes / 60);
        minutes = picker(0, 59);
        row.addView(hours);
        row.addView(Ui.text(this, "h", 18, Ui.TEXT, true));
        row.addView(minutes);
        row.addView(Ui.text(this, "m", 18, Ui.TEXT, true));
        col.addView(row, Ui.params(this, 8));
        hours.setValue(savedMinutes / 60);
        minutes.setValue(savedMinutes % 60);
        NumberPicker.OnValueChangeListener onChange = (p, o, n) -> updateSaveState();
        hours.setOnValueChangedListener(onChange);
        minutes.setOnValueChangedListener(onChange);

        note = Ui.text(this, "", 13, Ui.MUTED, false);
        col.addView(note, Ui.params(this, 10));

        save = Ui.button(this, "Save limit", true);
        save.setOnClickListener(v -> confirmSave());
        col.addView(save, Ui.params(this, 20));

        col.addView(Ui.text(this, "How it works", 14, Ui.MUTED, true), Ui.params(this, 32));
        col.addView(Ui.text(this, "Every second X is open counts toward the limit, and the count resets at 5:00 AM "
                + "in your phone's time zone (not midnight). Lowering the limit applies as soon as you save, "
                + "so a limit below today's usage locks X right away. Raising it starts tomorrow at 5:00 AM. "
                + "If you change it more than once, the last value you saved is tomorrow's limit. "
                + "The limit is always on, between " + Ui.hm(1) + " and " + Ui.hm(maxMinutes) + " in this build, "
                + "and it is kept in several places outside the app so reinstalling or clearing data does not reset it.",
                14, Ui.MUTED, false), Ui.params(this, 8));
        setContentView(Ui.scroll(this, col));
        updateSaveState();
    }

    private NumberPicker picker(int min, int max) {
        NumberPicker p = new NumberPicker(this);
        p.setMinValue(min);
        p.setMaxValue(max);
        p.setWrapSelectorWheel(false);
        p.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 80), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 8);
        lp.rightMargin = Ui.dp(this, 8);
        p.setLayoutParams(lp);
        return p;
    }

    private int pickedMinutes() {
        return hours.getValue() * 60 + minutes.getValue();
    }

    private boolean pickedValid() {
        int m = pickedMinutes();
        return m >= LimitConfig.MIN_MINUTES && m <= maxMinutes;
    }

    private boolean dirty() {
        return pickedMinutes() != savedMinutes;
    }

    private void updateSaveState() {
        int m = pickedMinutes();
        TimeLimit t = TimeLimit.get();
        TimeLimit.Snapshot s = t == null ? null : t.snapshot();
        if (!pickedValid()) {
            note.setText(m < LimitConfig.MIN_MINUTES ? "The limit must be at least 1 minute."
                    : "The limit cannot be more than " + Ui.hm(maxMinutes) + " in this build.");
            note.setTextColor(Ui.DANGER);
        } else if (!dirty()) {
            note.setText("This is your current limit. Pick a new value to change it.");
            note.setTextColor(Ui.MUTED);
        } else if (s != null && m < s.limitMinutes) {
            note.setText("Lower than today's limit: applies immediately when you save"
                    + (s.spentMs >= m * LimitEngine.MINUTE_MS ? " and will lock X for the rest of today." : "."));
            note.setTextColor(Ui.DANGER);
        } else if (s != null && m > s.limitMinutes) {
            note.setText("Higher than today's limit: starts tomorrow at 5:00 AM. Today stays at " + Ui.hm(s.limitMinutes) + ".");
            note.setTextColor(Ui.MUTED);
        } else {
            note.setText("Same as today's limit; tomorrow's limit becomes " + Ui.hm(m) + ".");
            note.setTextColor(Ui.MUTED);
        }
        Ui.setEnabledLook(save, pickedValid() && dirty());
    }

    private void confirmSave() {
        if (!pickedValid() || !dirty()) return;
        int m = pickedMinutes();
        TimeLimit t = TimeLimit.get();
        if (t == null) return;
        TimeLimit.Snapshot s = t.snapshot();
        String msg;
        if (m < s.limitMinutes) {
            msg = "Set the daily limit to " + Ui.hm(m) + "? This is lower than today's limit and applies right now"
                    + (s.spentMs >= m * LimitEngine.MINUTE_MS ? ", which locks X for the rest of today." : ".");
        } else {
            msg = "Set the daily limit to " + Ui.hm(m) + "? Today's limit stays " + Ui.hm(s.limitMinutes)
                    + "; the new limit starts tomorrow at 5:00 AM.";
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Save daily limit")
                .setMessage(msg)
                .setPositiveButton("Save", (d, w) -> {
                    boolean locked = t.saveLimit(m);
                    savedMinutes = m;
                    updateSaveState();
                    refresh();
                    if (locked) finish();   // the controller has already put the lock screen up
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refresh() {
        TimeLimit t = TimeLimit.get();
        if (t == null) return;
        TimeLimit.Snapshot s = t.snapshot();
        spentText.setText("Spent today: " + Ui.hms(s.spentMs) + "  /  " + Ui.hm(s.limitMinutes));
        long limitMs = s.limitMinutes * LimitEngine.MINUTE_MS;
        bar.setProgress((int) Math.min(1000, s.spentMs * 1000 / Math.max(1, limitMs)));
        String lt = "Today's limit: " + Ui.hm(s.limitMinutes);
        if (s.nextDayLimitMinutes != s.limitMinutes) lt += "  ·  tomorrow: " + Ui.hm(s.nextDayLimitMinutes);
        if (s.locked) lt += "  ·  locked until 5:00 AM";
        limitText.setText(lt);
        graph.set(s.historyMs, s.historyDays, limitMs);
    }

    @Override
    public void onBackPressed() {
        if (dirty() && pickedValid()) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Discard changes?")
                    .setMessage("Your new limit has not been saved.")
                    .setPositiveButton("Discard", (d, w) -> finish())
                    .setNegativeButton("Keep editing", null)
                    .show();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        TimeLimit t = TimeLimit.get();
        if (t != null) t.addListener(refresh);
        refresh();
        updateSaveState();
    }

    @Override
    protected void onPause() {
        TimeLimit t = TimeLimit.get();
        if (t != null) t.removeListener(refresh);
        super.onPause();
    }
}
