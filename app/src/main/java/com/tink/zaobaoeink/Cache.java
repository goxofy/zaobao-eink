package com.tink.zaobaoeink;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Tiny file cache in the app's private directory: raw JSON strings by name. */
public final class Cache {

    private Cache() {
    }

    private static File file(Context ctx, String name) {
        File dir = new File(ctx.getFilesDir(), "zb-cache");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, name);
    }

    public static String read(Context ctx, String name) {
        File f = file(ctx, name);
        if (!f.exists()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            int n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) != -1) {
                off += n;
            }
            return new String(buf, 0, off, "UTF-8");
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static void write(Context ctx, String name, String content) {
        File f = file(ctx, name);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(content.getBytes("UTF-8"));
        } catch (IOException ignored) {
            // Cache is best-effort only.
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static long mtime(Context ctx, String name) {
        File f = file(ctx, name);
        return f.exists() ? f.lastModified() : 0;
    }

    public static String listKey(String feed) {
        return "list_" + feed.replaceAll("[^a-zA-Z0-9]+", "_") + ".json";
    }

    public static String articleKey(String documentId) {
        return "article_" + documentId + ".json";
    }

    public static final String MENU_KEY = "menu.json";
}
