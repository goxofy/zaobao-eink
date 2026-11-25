package com.tink.zaobaoeink;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * List thumbnails (only used when the user enables images): download, down-sample
 * to display size, convert to grayscale immediately. No originals are cached and
 * nothing is pre-fetched; the small LRU holds only decoded grayscale thumbnails.
 */
public final class Images {

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(2 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    /** Decoded full-width article images are far bigger than list thumbnails. */
    private static final LruCache<String, Bitmap> BODY_CACHE =
            new LruCache<String, Bitmap>(10 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private Images() {
    }

    public static Bitmap cached(String url, int targetPx) {
        return CACHE.get(url + "@" + targetPx);
    }

    /** Blocking load; call on the background executor. Returns null on any failure. */
    public static Bitmap load(String urlStr, int targetPx) {
        String key = urlStr + "@" + targetPx;
        Bitmap hit = CACHE.get(key);
        if (hit != null) return hit;
        Bitmap b = fetch(urlStr, targetPx);
        if (b != null) CACHE.put(key, b);
        return b;
    }

    /**
     * Article body image: down-sampled around the text column width, grayscaled,
     * and scaled to at most the column width so cached bitmaps stay bounded.
     * Blocking; call on the background executor. Returns null on any failure.
     */
    public static Bitmap loadArticleImage(String urlStr, int targetPx) {
        String key = urlStr + "@" + targetPx;
        Bitmap hit = BODY_CACHE.get(key);
        if (hit != null) return hit;
        Bitmap b = fetch(urlStr, targetPx);
        if (b == null) return null;
        if (b.getWidth() > targetPx) {
            int nh = Math.max(1, (int) ((long) b.getHeight() * targetPx / b.getWidth()));
            Bitmap scaled = Bitmap.createScaledBitmap(b, targetPx, nh, true);
            if (scaled != b) {
                b.recycle();
                b = scaled;
            }
        }
        BODY_CACHE.put(key, b);
        return b;
    }

    /** Network fetch + decode + grayscale, no caching. Background thread only. */
    private static Bitmap fetch(String urlStr, int targetPx) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(16384);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            byte[] data = bos.toByteArray();

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int sample = 1;
            int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
            while (maxDim / (sample * 2) >= targetPx && sample < 32) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap raw = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            if (raw == null) return null;
            Bitmap gray = toGrayscale(raw);
            if (gray != raw) raw.recycle();
            return gray;
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            if (conn != null) conn.disconnect();
        }
    }

    private static Bitmap toGrayscale(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint();
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0f);
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(src, 0, 0, paint);
        return out;
    }
}
