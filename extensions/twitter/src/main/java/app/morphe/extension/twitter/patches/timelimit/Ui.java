package app.morphe.extension.twitter.patches.timelimit;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Tiny view helpers so the three screens match X's dark look without any layout resources. */
final class Ui {
    static final int BG = 0xFF000000;
    static final int TEXT = 0xFFE7E9EA;
    static final int MUTED = 0xFF71767B;
    static final int ACCENT = 0xFF1D9BF0;
    static final int TRACK = 0xFF2F3336;
    static final int DANGER = 0xFFF4212E;

    private Ui() {}

    static int dp(Context c, int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, c.getResources().getDisplayMetrics());
    }

    static void styleWindow(Activity a) {
        Window w = a.getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        w.getDecorView().setBackgroundColor(BG);
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 24);
        l.setPadding(p, dp(c, 48), p, p);
        l.setBackgroundColor(BG);
        return l;
    }

    static ScrollView scroll(Context c, View child) {
        ScrollView s = new ScrollView(c);
        s.setBackgroundColor(BG);
        s.setFillViewport(true);
        s.addView(child);
        return s;
    }

    static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    static LinearLayout.LayoutParams params(Context c, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(c, topDp);
        return p;
    }

    static Button button(Context c, String label, boolean primary) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? Color.WHITE : TEXT);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(c, 24));
        if (primary) {
            bg.setColor(ACCENT);
        } else {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(dp(c, 1), MUTED);
        }
        b.setBackground(bg);
        b.setPadding(dp(c, 20), dp(c, 12), dp(c, 20), dp(c, 12));
        b.setGravity(Gravity.CENTER);
        return b;
    }

    static void setEnabledLook(Button b, boolean enabled) {
        b.setEnabled(enabled);
        b.setAlpha(enabled ? 1f : 0.4f);
    }

    static String hms(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return h + "h " + m + "m " + sec + "s";
        return m + "m " + sec + "s";
    }

    static String hm(int minutes) {
        int h = minutes / 60, m = minutes % 60;
        if (h > 0 && m > 0) return h + "h " + m + "m";
        if (h > 0) return h + "h";
        return m + "m";
    }
}
