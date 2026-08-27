package jp.rstlab.batteryrelay.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import jp.rstlab.batteryrelay.core.TrendMath;
import jp.rstlab.batteryrelay.model.BatterySample;

/** Exact 30-minute x-axis with a locally auto-scaled y-axis to keep small changes visible. */
public final class TrendChartView extends View {
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF plotRect = new RectF();
    private final Path linePath = new Path();
    private List<BatterySample> samples = Collections.emptyList();
    private TrendMath.Metric metric = TrendMath.Metric.BATTERY_PERCENT;
    private long referenceTime = System.currentTimeMillis();
    private int lineColor;

    public TrendChartView(Context context) {
        super(context);
        init();
    }

    public TrendChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        lineColor = Ui.terracotta(getContext());
        gridPaint.setColor(Ui.border(getContext()));
        gridPaint.setStrokeWidth(Ui.dp(getContext(), 1));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeWidth(Ui.dp(getContext(), 2.5f));
        dotPaint.setStyle(Paint.Style.FILL);
        labelPaint.setColor(Ui.subtext(getContext()));
        labelPaint.setTextSize(Ui.dp(getContext(), 10));
        labelPaint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));
        emptyPaint.setColor(Ui.subtext(getContext()));
        emptyPaint.setTextSize(Ui.dp(getContext(), 13));
        emptyPaint.setTextAlign(Paint.Align.CENTER);
        setMinimumHeight(Ui.dp(getContext(), 190));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setData(List<BatterySample> values, TrendMath.Metric metric, int color, long referenceTime) {
        this.samples = values == null ? Collections.emptyList() : new ArrayList<>(values);
        this.metric = metric;
        this.lineColor = color;
        this.referenceTime = referenceTime > 0 ? referenceTime : System.currentTimeMillis();
        updateDescription();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = Ui.dp(getContext(), 42);
        float top = Ui.dp(getContext(), 12);
        float right = getWidth() - Ui.dp(getContext(), 10);
        float bottom = getHeight() - Ui.dp(getContext(), 28);
        if (right <= left || bottom <= top) return;
        plotRect.set(left, top, right, bottom);
        RectF plot = plotRect;

        for (int i = 0; i < 3; i++) {
            float y = top + (bottom - top) * i / 2f;
            canvas.drawLine(left, y, right, y, gridPaint);
        }
        canvas.drawLine(left, top, left, bottom, gridPaint);

        long start = referenceTime - TrendMath.WINDOW_MILLIS;
        List<Point> points = pointsWithin(start, referenceTime);
        Range range = range(points);
        drawAxisLabels(canvas, plot, range);
        if (points.size() < 2) {
            canvas.drawText("推移データを収集中（約1分）", plot.centerX(), plot.centerY(), emptyPaint);
            if (points.size() == 1) drawDot(canvas, plot, points.get(0), range, start);
            return;
        }

        linePath.reset();
        Path path = linePath;
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            float x = xFor(point.time, plot, start);
            float y = yFor(point.value, plot, range);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        linePaint.setColor(lineColor);
        dotPaint.setColor(lineColor);
        canvas.drawPath(path, linePaint);
        drawDot(canvas, plot, points.get(points.size() - 1), range, start);
    }

    private void drawAxisLabels(Canvas canvas, RectF plot, Range range) {
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(formatValue(range.max), plot.left - Ui.dp(getContext(), 7),
                plot.top + Ui.dp(getContext(), 4), labelPaint);
        canvas.drawText(formatValue((range.min + range.max) / 2d),
                plot.left - Ui.dp(getContext(), 7), plot.centerY() + Ui.dp(getContext(), 4), labelPaint);
        canvas.drawText(formatValue(range.min), plot.left - Ui.dp(getContext(), 7),
                plot.bottom, labelPaint);

        labelPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("−30分", plot.left, getHeight() - Ui.dp(getContext(), 7), labelPaint);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("−15分", plot.centerX(), getHeight() - Ui.dp(getContext(), 7), labelPaint);
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("今", plot.right, getHeight() - Ui.dp(getContext(), 7), labelPaint);
    }

    private void drawDot(Canvas canvas, RectF plot, Point point, Range range, long start) {
        dotPaint.setColor(lineColor);
        canvas.drawCircle(xFor(point.time, plot, start), yFor(point.value, plot, range),
                Ui.dp(getContext(), 4), dotPaint);
    }

    private List<Point> pointsWithin(long start, long end) {
        ArrayList<Point> points = new ArrayList<>();
        for (BatterySample sample : samples) {
            if (sample.timestampMillis < start || sample.timestampMillis > end) continue;
            double value = metric == TrendMath.Metric.BATTERY_PERCENT
                    ? sample.levelPercent >= 0 ? sample.levelPercent : Double.NaN
                    : sample.temperatureC;
            if (Double.isFinite(value)) points.add(new Point(sample.timestampMillis, value));
        }
        points.sort((a, b) -> Long.compare(a.time, b.time));
        return points;
    }

    private Range range(List<Point> points) {
        if (points.isEmpty()) {
            return metric == TrendMath.Metric.BATTERY_PERCENT ? new Range(0, 100) : new Range(20, 45);
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (Point point : points) {
            min = Math.min(min, point.value);
            max = Math.max(max, point.value);
        }
        double padding = Math.max(metric == TrendMath.Metric.BATTERY_PERCENT ? 1d : 0.5d,
                (max - min) * 0.2d);
        min -= padding;
        max += padding;
        if (metric == TrendMath.Metric.BATTERY_PERCENT) {
            min = Math.max(0d, min);
            max = Math.min(100d, max);
        }
        if (max - min < 0.1d) max = min + 1d;
        return new Range(min, max);
    }

    private float xFor(long time, RectF plot, long start) {
        double fraction = (time - start) / (double) TrendMath.WINDOW_MILLIS;
        fraction = Math.max(0d, Math.min(1d, fraction));
        return (float) (plot.left + plot.width() * fraction);
    }

    private static float yFor(double value, RectF plot, Range range) {
        double fraction = (value - range.min) / (range.max - range.min);
        return (float) (plot.bottom - plot.height() * fraction);
    }

    private String formatValue(double value) {
        return metric == TrendMath.Metric.BATTERY_PERCENT
                ? String.format(Locale.JAPAN, "%.0f%%", value)
                : String.format(Locale.JAPAN, "%.1f°", value);
    }

    private void updateDescription() {
        String name = metric == TrendMath.Metric.BATTERY_PERCENT ? "バッテリー残量" : "バッテリー温度";
        setContentDescription(name + "の直近30分グラフ。1分単位で表示");
    }

    private static final class Point {
        final long time;
        final double value;
        Point(long time, double value) { this.time = time; this.value = value; }
    }

    private static final class Range {
        final double min;
        final double max;
        Range(double min, double max) { this.min = min; this.max = max; }
    }
}
