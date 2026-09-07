package app.morphe.extension.twitter.patches.timelimit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Seven bars, oldest on the left, today highlighted, with the limit as a dashed line. */
final class UsageGraphView extends View {
    private long[] values = new long[7];
    private int[] days = new int[7];
    private long limitMs = 30 * 60_000L;
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint today = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);

    UsageGraphView(Context c) {
        super(c);
        bar.setColor(Ui.MUTED);
        today.setColor(Ui.ACCENT);
        line.setColor(Ui.DANGER);
        line.setStrokeWidth(Ui.dp(c, 1));
        line.setPathEffect(new android.graphics.DashPathEffect(new float[]{Ui.dp(c, 4), Ui.dp(c, 4)}, 0));
        label.setColor(Ui.MUTED);
        label.setTextSize(Ui.dp(c, 11));
        label.setTextAlign(Paint.Align.CENTER);
        track.setColor(Ui.TRACK);
    }

    void set(long[] values, int[] days, long limitMs) {
        this.values = values;
        this.days = days;
        this.limitMs = limitMs;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(getContext(), 120));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        int labelH = Ui.dp(getContext(), 16);
        int top = Ui.dp(getContext(), 6);
        int plotH = h - labelH - top;
        long max = limitMs;
        for (long v : values) max = Math.max(max, v);
        if (max <= 0) max = 1;
        int n = values.length;
        float slot = (float) w / n;
        float bw = slot * 0.55f;
        for (int i = 0; i < n; i++) {
            float cx = slot * i + slot / 2;
            float bh = plotH * (values[i] / (float) max);
            RectF r = new RectF(cx - bw / 2, top + plotH - bh, cx + bw / 2, top + plotH);
            c.drawRoundRect(new RectF(cx - bw / 2, top, cx + bw / 2, top + plotH), 6, 6, track);
            if (values[i] > 0) c.drawRoundRect(r, 6, 6, i == n - 1 ? today : bar);
            int d = days[i];
            c.drawText(i == n - 1 ? "today" : (d % 100) + "", cx, h - 3, label);
        }
        float ly = top + plotH - plotH * (limitMs / (float) max);
        c.drawLine(0, ly, w, ly, line);
    }
}
