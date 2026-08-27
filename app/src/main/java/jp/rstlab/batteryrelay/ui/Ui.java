package jp.rstlab.batteryrelay.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

public final class Ui {
    private Ui() {}

    public static boolean isDark(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int canvas(Context context) { return isDark(context) ? Color.rgb(23, 26, 27) : Color.rgb(246, 242, 234); }
    public static int card(Context context) { return isDark(context) ? Color.rgb(35, 39, 41) : Color.rgb(255, 253, 248); }
    public static int text(Context context) { return isDark(context) ? Color.rgb(239, 236, 227) : Color.rgb(37, 43, 46); }
    public static int subtext(Context context) { return isDark(context) ? Color.rgb(175, 178, 174) : Color.rgb(102, 108, 109); }
    public static int border(Context context) { return isDark(context) ? Color.rgb(59, 64, 65) : Color.rgb(222, 216, 205); }
    public static int terracotta(Context context) { return isDark(context) ? Color.rgb(215, 133, 104) : Color.rgb(190, 87, 57); }
    public static int slate(Context context) { return isDark(context) ? Color.rgb(128, 159, 184) : Color.rgb(76, 105, 132); }
    public static int success(Context context) { return isDark(context) ? Color.rgb(132, 171, 139) : Color.rgb(62, 119, 75); }
    public static int mutedFill(Context context) { return isDark(context) ? Color.rgb(43, 47, 49) : Color.rgb(239, 234, 225); }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static TextView text(Context context, String value, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.create("sans", Typeface.BOLD));
        return view;
    }

    public static GradientDrawable rounded(Context context, int fill, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable outlined(Context context, int fill, int stroke, float radiusDp) {
        GradientDrawable drawable = rounded(context, fill, radiusDp);
        drawable.setStroke(dp(context, 1), stroke);
        return drawable;
    }

    public static void styleButton(TextView button, boolean primary) {
        Context context = button.getContext();
        int fill = primary ? text(context) : card(context);
        int label = primary ? canvas(context) : text(context);
        GradientDrawable content = outlined(context, fill,
                primary ? fill : border(context), 14f);
        int ripple = primary ? Color.argb(45, 255, 255, 255) : Color.argb(28, 40, 40, 40);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), content, null));
        button.setTextColor(label);
        button.setTextSize(15f);
        button.setTypeface(Typeface.create("sans", Typeface.BOLD));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 52));
        button.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10));
        button.setClickable(true);
        button.setFocusable(true);
    }

    public static void styleChip(TextView chip, boolean selected) {
        Context context = chip.getContext();
        int fill = selected ? text(context) : mutedFill(context);
        int label = selected ? canvas(context) : text(context);
        int stroke = selected ? text(context) : border(context);
        GradientDrawable content = outlined(context, fill, stroke, 99f);
        int ripple = selected ? Color.argb(42, 255, 255, 255) : Color.argb(28, 40, 40, 40);
        chip.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), content, null));
        chip.setTextColor(label);
        chip.setTextSize(12.5f);
        chip.setTypeface(Typeface.create("sans", Typeface.BOLD));
        chip.setGravity(Gravity.CENTER);
        // Material/Android accessibility guidance: interactive targets are at least 48dp.
        chip.setMinHeight(dp(context, 48));
        chip.setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8));
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setSelected(selected);
    }

    public static void setMargins(View view, int left, int top, int right, int bottom) {
        if (!(view.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams)) return;
        android.view.ViewGroup.MarginLayoutParams params =
                (android.view.ViewGroup.MarginLayoutParams) view.getLayoutParams();
        params.setMargins(left, top, right, bottom);
        view.setLayoutParams(params);
    }
}
