package com.eurobuddha.ethwallet;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/** Dark + orange palette and a few view helpers, matching the minimaCore app family. */
public final class Design {

    public static final int BG       = 0xFF0A0A0F;
    public static final int SURFACE  = 0xFF15151F;
    public static final int SURFACE2 = 0xFF1F1F2B;
    public static final int ACCENT   = 0xFFF7931A;   // Minima orange
    public static final int ON_ACCENT = 0xFF000000;
    public static final int TEXT     = 0xFFFFFFFF;
    public static final int DIM      = 0xFF9A9AA8;
    public static final int DIM2     = 0xFF6A6A78;
    public static final int RED      = 0xFFE74C3C;

    public static int dp(Context c, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** A rounded chip-style TextView. */
    public static TextView pill(Context c, String text, int bg, int fg) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(fg);
        t.setTextSize(11f);
        t.setGravity(Gravity.CENTER);
        int h = dp(c, 6), w = dp(c, 10);
        t.setPadding(w, h, w, h);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(c, 14));
        t.setBackground(d);
        return t;
    }

    /** A rounded filled background drawable (for cards / inputs). */
    public static GradientDrawable roundBg(Context c, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    private Design() {}
}
