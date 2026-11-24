package com.tink.zaobaoeink;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings: article font-size level and the list-image switch. */
public final class Prefs {

    private static final String NAME = "zb_prefs";
    public static final int[] FONT_SIZES_SP = {17, 19, 21, 24};

    private Prefs() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static int fontLevel(Context ctx) {
        int v = sp(ctx).getInt("fontLevel", 1);
        if (v < 0) v = 0;
        if (v >= FONT_SIZES_SP.length) v = FONT_SIZES_SP.length - 1;
        return v;
    }

    public static void setFontLevel(Context ctx, int level) {
        if (level < 0) level = 0;
        if (level >= FONT_SIZES_SP.length) level = FONT_SIZES_SP.length - 1;
        sp(ctx).edit().putInt("fontLevel", level).apply();
    }

    public static boolean imagesEnabled(Context ctx) {
        return sp(ctx).getBoolean("images", false);
    }

    public static void setImagesEnabled(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean("images", on).apply();
    }
}
