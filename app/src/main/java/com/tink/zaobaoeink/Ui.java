package com.tink.zaobaoeink;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small shared UI helpers: dp conversion, e-ink styled widgets, time formatting. */
public final class Ui {

    public static final int BLACK = Color.BLACK;
    public static final int GREY = Color.rgb(0x44, 0x44, 0x44);
    public static final int LIGHT_GREY_BG = Color.rgb(0xEE, 0xEE, 0xEE);

    private Ui() {
    }

    public static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static TextView text(Context ctx, String s, float sp, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(BLACK);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /**
     * Flat e-ink button: white background, black border, no ripple/shadow
     * (API 23 default state list is replaced). While pressed it inverts to
     * black background / white text — on an e-ink screen with slow refresh
     * and no haptics, that single inverted frame is the only confirmation
     * that a tap actually landed, so every button in the app gets it here.
     */
    public static Button button(Context ctx, String s) {
        Button b = new Button(ctx);
        b.setText(s);
        b.setTextSize(15);
        b.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_pressed}, {}},
                new int[]{Color.WHITE, BLACK}));
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.WHITE);
        normal.setStroke(2, BLACK);
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(BLACK);
        pressed.setStroke(2, BLACK);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed}, pressed);
        bg.addState(new int[]{}, normal);
        b.setBackground(bg);
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    public static View divider(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(BLACK);
        return v;
    }

    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);

    public static String formatTime(long epochSec) {
        if (epochSec <= 0) return "";
        return FMT.format(new Date(epochSec * 1000L));
    }
}
