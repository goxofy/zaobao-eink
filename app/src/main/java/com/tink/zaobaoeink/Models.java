package com.tink.zaobaoeink;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;

/** Plain data holders + JSON -> model extraction (org.json only). */
public final class Models {

    private Models() {
    }

    /** A level-2 menu entry that can be turned into a list request. */
    public static final class Section {
        public String name = "";
        public String code = "";
        public String feed = "";
        public String targetId = "";
        public String targetUrl = "";
        public String targetName = "";
        public String targetCategory = "";
    }

    /** One flattened article card from a list response. */
    public static final class Card {
        public String documentId = "";
        public String headline = "";
        public String sectionName = "";
        public String thumb = "";
        public long pubTimeSec = 0;
        public boolean paid = false;
    }

    static String strOf(Object v) {
        if (v == null || v == JSONObject.NULL) return "";
        return String.valueOf(v);
    }

    static Section toSection(JSONObject o) {
        Section s = new Section();
        s.name = o.optString("name", "");
        s.code = strOf(o.opt("code"));
        s.feed = o.optString("feed", "");
        s.targetId = strOf(o.opt("targetId"));
        s.targetUrl = o.optString("targetUrl", "");
        s.targetName = o.optString("targetName", "");
        s.targetCategory = o.optString("targetCategory", "");
        if (s.targetUrl.length() == 0) s.targetUrl = s.feed;
        return s;
    }

    static Card toCard(JSONObject o) {
        Object idv = o.opt("documentId");
        if (idv == null || idv == JSONObject.NULL) return null;
        Card c = new Card();
        c.documentId = String.valueOf(idv);
        if (c.documentId.length() == 0) return null;
        c.headline = o.optString("headline", "");
        if (c.headline.length() == 0) return null;
        JSONObject sec = o.optJSONObject("section");
        if (sec != null) c.sectionName = sec.optString("name", "");
        c.paid = o.optBoolean("paid", false);
        c.pubTimeSec = o.optLong("publicationTime", 0);
        JSONArray imgs = o.optJSONArray("images");
        if (imgs != null && imgs.length() > 0) {
            JSONObject im = imgs.optJSONObject(0);
            if (im != null) {
                c.thumb = im.optString("thumbnail", "");
                if (c.thumb.length() == 0) c.thumb = im.optString("url", "");
            }
        }
        return c;
    }

    /**
     * Recursively walk a list-response node; collect every object that looks like an
     * article card (has documentId + headline), de-duplicated, in traversal order.
     * Only the card's own fields are kept; the raw tree can be dropped afterwards.
     */
    static void flattenInto(Object node, LinkedHashMap<String, Card> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (o.has("documentId") && o.has("headline")) {
                Card c = toCard(o);
                if (c != null && !out.containsKey(c.documentId)) {
                    out.put(c.documentId, c);
                }
                return;
            }
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                flattenInto(o.opt(keys.next()), out);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                flattenInto(a.opt(i), out);
            }
        }
    }
}
