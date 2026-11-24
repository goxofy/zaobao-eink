package com.tink.zaobaoeink;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Zaobao content API client. Plain HttpURLConnection + manual gzip.
 *
 * Notes from the 2026-11-24 capture / replay tests:
 * - No login, no signature: the server does not validate X-Sign headers.
 * - Every v2 request carries the query trio platform/version/apiVersion.
 * - The list endpoint has a stateful server-side check ("40401 数据状态参数不在
 *   允许范围内") that can reject bare requests for minutes at a time; sending the
 *   same device headers the real app sends (platform / apiVersion / appVersion /
 *   ssid / deviceId) plus retrying 40401 with backoff makes it reliable.
 */
public final class Api {

    static final String BASE = "https://nsdd.zbstatic5.com/appapi/";
    private static final String TRIO = "platform=Android&version=7.1.8&apiVersion=1.2";
    private static final int TIMEOUT_MS = 15000;

    private static String ssid;
    private static String deviceId;

    private Api() {
    }

    public static final class ApiException extends Exception {
        public ApiException(String msg) {
            super(msg);
        }
    }

    /** Load (or create once) the persistent guest ids. Cheap; call on the UI thread. */
    public static synchronized void init(Context ctx) {
        if (ssid != null) return;
        SharedPreferences sp = ctx.getSharedPreferences("zb_ids", Context.MODE_PRIVATE);
        ssid = sp.getString("ssid", null);
        deviceId = sp.getString("deviceId", null);
        if (ssid == null || deviceId == null) {
            ssid = UUID.randomUUID().toString();
            deviceId = UUID.randomUUID().toString();
            sp.edit().putString("ssid", ssid).putString("deviceId", deviceId).apply();
        }
    }

    // ---------- URL builders ----------

    public static String menuUrl() {
        return BASE + "v2/navigation/menu?" + TRIO;
    }

    public static String listUrl(Models.Section s, int pageNo) {
        StringBuilder u = new StringBuilder(BASE);
        u.append("v2/news/list").append(s.feed).append('?');
        boolean customize = !"sitemap".equals(s.targetCategory);
        if (!customize && s.targetId.length() > 0) {
            u.append("targetId=").append(enc(s.targetId)).append('&');
        }
        u.append("targetUrl=").append(enc(s.targetUrl));
        u.append("&targetName=").append(enc(s.targetName));
        u.append("&targetType=").append(customize ? "customize" : "sitemap");
        u.append("&newsCategoriesId=").append(enc(s.code));
        if (customize) u.append("&recApiVersion=V2");
        u.append("&pageNo=").append(pageNo);
        u.append("&pageSize=20");
        u.append('&').append(TRIO);
        return u.toString();
    }

    public static String articleUrl(String documentId) {
        return BASE + "v2/feed/article/zb/" + documentId + "?" + TRIO;
    }

    private static String enc(String v) {
        try {
            return URLEncoder.encode(v == null ? "" : v, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    // ---------- HTTP ----------

    /** Raw GET, returns body as UTF-8 string. Throws IOException on transport/HTTP errors. */
    public static String get(String urlStr) throws IOException {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.setRequestProperty("User-Agent",
                    "Zaobao (Linux; Android 13; IN2020; arm64-v8a) version/7.1.8 Language/zh-Hans-CN");
            if (ssid != null) {
                conn.setRequestProperty("ssid", ssid);
                conn.setRequestProperty("x-user-ssid", ssid);
                conn.setRequestProperty("deviceId", deviceId);
            }
            conn.setRequestProperty("platform", "Android");
            conn.setRequestProperty("apiVersion", "1.2");
            conn.setRequestProperty("appVersion", "7.1.8");

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            in = conn.getInputStream();
            String enc = conn.getContentEncoding();
            if (enc != null && enc.contains("gzip")) {
                in = new GZIPInputStream(in);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
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

    /**
     * GET + envelope check. Returns the raw body when the envelope code is "200"
     * (callers parse and cache the raw string themselves).
     * Envelope code 40401 is retried (3 attempts total, backoff 0.8s / 1.6s) because
     * the server-side state check is flaky; a single retry proved insufficient.
     */
    public static String fetchRaw(String url) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(attempt == 1 ? 800 : 1600);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ApiException("已取消");
                }
            }
            String body = get(url);
            JSONObject root;
            try {
                root = new JSONObject(body);
            } catch (Exception e) {
                throw new ApiException("服务器返回了无法解析的内容");
            }
            String code = root.optString("code", "");
            if ("200".equals(code)) {
                return body;
            }
            String msg = root.optString("message", "");
            last = new ApiException("接口错误 " + code + (msg.length() > 0 ? "：" + msg : ""));
            if (!"40401".equals(code)) {
                throw last;
            }
        }
        throw last != null ? last : new ApiException("接口错误");
    }
}
