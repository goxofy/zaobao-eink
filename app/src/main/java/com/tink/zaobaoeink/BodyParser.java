package com.tink.zaobaoeink;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.StyleSpan;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hand-written lightweight parser: article body HTML -> structured Spanned document.
 *
 * Preserved: paragraphs, h2/h3 headings, blockquote indent blocks, ul/li items,
 * figure/img positions (as tappable "[图片]" placeholder paragraphs), bold/italic
 * inline styles and <a href> links (LinkSpan). Ad divs are dropped; unknown tags
 * degrade to plain paragraph text. No third-party parser.
 */
public final class BodyParser {

    /** A tappable link inside the body text. */
    public static class LinkSpan extends ClickableSpan {
        public final String url;

        public LinkSpan(String url) {
            this.url = url;
        }

        @Override
        public void onClick(View widget) {
            // Clicks are dispatched by ArticleActivity's own touch handler.
        }

        @Override
        public void updateDrawState(android.text.TextPaint ds) {
            ds.setColor(Color.BLACK);
            ds.setUnderlineText(true);
        }
    }

    /** A tappable "[图片]" placeholder that keeps the image's position in the flow. */
    public static class ImageSpan extends ClickableSpan {
        public final String url;

        public ImageSpan(String url) {
            this.url = url;
        }

        @Override
        public void onClick(View widget) {
            // Clicks are dispatched by ArticleActivity's own touch handler.
        }

        @Override
        public void updateDrawState(android.text.TextPaint ds) {
            ds.setColor(Color.BLACK);
            ds.setUnderlineText(false);
        }
    }

    private static final int P_NORMAL = 0;
    private static final int P_H2 = 1;
    private static final int P_H3 = 2;
    private static final int P_QUOTE = 3;
    private static final int P_ITEM = 4;
    private static final int P_CAPTION = 5;
    private static final int P_IMAGE = 6;

    private final SpannableStringBuilder out = new SpannableStringBuilder();
    private final int bodyPx;
    private final int h2Px;
    private final int h3Px;
    private final int captionPx;
    private final int marginPx;

    private int paraStart = -1;
    private int paraKind = P_NORMAL;
    private boolean inQuote = false;
    private int skipDepth = 0;      // >0 while inside an ad block (counts nested divs)
    private String skipTag = null;  // tag name whose block is being skipped (script/style)
    private final List<Inline> inlineStack = new ArrayList<Inline>();

    private static final class Inline {
        final String type; // "b", "i", "a"
        final int start;
        final String href;

        Inline(String type, int start, String href) {
            this.type = type;
            this.start = start;
            this.href = href;
        }
    }

    private BodyParser(int bodyPx, float density) {
        this.bodyPx = bodyPx;
        this.h2Px = (int) (bodyPx * 1.30f);
        this.h3Px = (int) (bodyPx * 1.15f);
        this.captionPx = Math.max(10, (int) (bodyPx * 0.82f));
        this.marginPx = (int) (26 * density + 0.5f);
    }

    public static SpannableStringBuilder parse(String html, int bodyPx, float density) {
        BodyParser p = new BodyParser(bodyPx, density);
        p.run(html == null ? "" : html);
        return p.out;
    }

    // ---------------- tokenizer ----------------

    private void run(String html) {
        int i = 0;
        int n = html.length();
        while (i < n) {
            int lt = html.indexOf('<', i);
            if (lt < 0) {
                onText(html.substring(i));
                break;
            }
            if (lt > i) onText(html.substring(i, lt));
            int gt = html.indexOf('>', lt);
            if (gt < 0) break;
            String rawTag = html.substring(lt + 1, gt);
            onTag(rawTag);
            i = gt + 1;
        }
        closeParagraph();
        closeAllInline();
    }

    private void onText(String raw) {
        if (skipDepth > 0 || skipTag != null) return;
        String t = decodeEntities(raw).replaceAll("\\s+", " ");
        if (t.length() == 0) return;
        if (paraStart < 0) startParagraph(inQuote ? P_QUOTE : P_NORMAL);
        // Avoid a leading space right after a paragraph break / indent.
        if (out.length() > 0) {
            char last = out.charAt(out.length() - 1);
            if ((last == '\n' || last == '　') && t.startsWith(" ")) {
                t = t.substring(1);
                if (t.length() == 0) return;
            }
        }
        out.append(t);
    }

    private void onTag(String rawTag) {
        String tag = rawTag.trim();
        if (tag.length() == 0) return;
        boolean closing = tag.startsWith("/");
        if (closing) tag = tag.substring(1).trim();
        boolean selfClose = tag.endsWith("/");
        if (selfClose) tag = tag.substring(0, tag.length() - 1).trim();
        int sp = indexOfSpace(tag);
        String name = (sp < 0 ? tag : tag.substring(0, sp)).toLowerCase(Locale.US);
        String attrs = sp < 0 ? "" : tag.substring(sp + 1);
        String attrsLow = attrs.toLowerCase(Locale.US);

        // Skipping an ad / script / style block?
        if (skipTag != null) {
            if (closing && name.equals(skipTag)) skipTag = null;
            return;
        }
        if (skipDepth > 0) {
            if (!closing && name.equals("div") && !selfClose) skipDepth++;
            else if (closing && name.equals("div")) skipDepth--;
            return;
        }

        if (name.equals("script") || name.equals("style")) {
            if (!closing && !selfClose) skipTag = name;
            return;
        }
        if (name.equals("div")) {
            if (!closing) {
                if (!selfClose && isAdDiv(attrsLow)) {
                    skipDepth = 1;
                    return;
                }
                if (attrsLow.contains("pic-title") || attrsLow.contains("caption")) {
                    startParagraph(P_CAPTION);
                } else {
                    paragraphBreak();
                }
            } else {
                paragraphBreak();
            }
            return;
        }
        if (name.equals("p")) {
            if (!closing) startParagraph(inQuote ? P_QUOTE : P_NORMAL);
            else closeParagraph();
            return;
        }
        if (name.equals("h2") || name.equals("h3")) {
            if (!closing) startParagraph(name.equals("h2") ? P_H2 : P_H3);
            else closeParagraph();
            return;
        }
        if (name.equals("blockquote")) {
            if (!closing) {
                inQuote = true;
                startParagraph(P_QUOTE);
            } else {
                inQuote = false;
                closeParagraph();
            }
            return;
        }
        if (name.equals("ul") || name.equals("ol")) {
            paragraphBreak();
            return;
        }
        if (name.equals("li")) {
            if (!closing) startParagraph(P_ITEM);
            else closeParagraph();
            return;
        }
        if (name.equals("figure") || name.equals("figcaption")) {
            if (!closing) {
                startParagraph(name.equals("figcaption") ? P_CAPTION : P_NORMAL);
            } else {
                closeParagraph();
            }
            return;
        }
        if (name.equals("img")) {
            if (!closing) {
                String src = attrValue(attrs, "src");
                if (src.length() == 0) src = attrValue(attrs, "data-src");
                addImagePlaceholder(src);
            }
            return;
        }
        if (name.equals("br")) {
            if (paraStart >= 0) out.append('\n');
            return;
        }
        if (name.equals("b") || name.equals("strong")) {
            if (!closing) pushInline("b", null);
            else popInline("b");
            return;
        }
        if (name.equals("i") || name.equals("em")) {
            if (!closing) pushInline("i", null);
            else popInline("i");
            return;
        }
        if (name.equals("a")) {
            if (!closing) pushInline("a", attrValue(attrs, "href"));
            else popInline("a");
            return;
        }
        if (name.equals("section") || name.equals("article") || name.equals("header")
                || name.equals("footer") || name.equals("table") || name.equals("tr")) {
            paragraphBreak();
            return;
        }
        // Unknown tags: drop the tag, keep inner text (degrade gracefully).
    }

    // ---------------- paragraphs & spans ----------------

    private void startParagraph(int kind) {
        closeParagraph();
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
        if ((kind == P_H2 || kind == P_H3) && out.length() > 0
                && !endsWithBlankLine()) {
            out.append('\n'); // breathing room before a heading
        }
        paraStart = out.length();
        paraKind = kind;
        if (kind == P_NORMAL) {
            out.append("　　");
        } else if (kind == P_ITEM) {
            out.append("• ");
        }
    }

    private void paragraphBreak() {
        closeParagraph();
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
    }

    private boolean endsWithBlankLine() {
        int len = out.length();
        return len >= 2 && out.charAt(len - 1) == '\n' && out.charAt(len - 2) == '\n';
    }

    private void closeParagraph() {
        if (paraStart < 0) return;
        int end = out.length();
        // Trim trailing spaces/newlines belonging to this paragraph (keep structure tight).
        while (end > paraStart && (out.charAt(end - 1) == ' ')) end--;
        if (end > paraStart) {
            switch (paraKind) {
                case P_H2:
                    out.setSpan(new StyleSpan(Typeface.BOLD), paraStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new AbsoluteSizeSpan(h2Px), paraStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case P_H3:
                    out.setSpan(new StyleSpan(Typeface.BOLD), paraStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new AbsoluteSizeSpan(h3Px), paraStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case P_QUOTE:
                    out.setSpan(new LeadingMarginSpan.Standard(marginPx), paraStart, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new ForegroundColorSpan(Color.rgb(0x33, 0x33, 0x33)), paraStart, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case P_ITEM:
                    out.setSpan(new LeadingMarginSpan.Standard(0, marginPx), paraStart, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case P_CAPTION:
                    out.setSpan(new AbsoluteSizeSpan(captionPx), paraStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new ForegroundColorSpan(Color.rgb(0x44, 0x44, 0x44)), paraStart, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                default:
                    break;
            }
        }
        paraStart = -1;
    }

    private void addImagePlaceholder(String url) {
        closeParagraph();
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
        int start = out.length();
        out.append("[图片]");
        int end = out.length();
        out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (url != null && url.length() > 0) {
            out.setSpan(new ImageSpan(decodeEntities(url)), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        out.append('\n');
        paraStart = -1;
    }

    private void pushInline(String type, String href) {
        if (paraStart < 0) startParagraph(inQuote ? P_QUOTE : P_NORMAL);
        inlineStack.add(new Inline(type, out.length(), href));
    }

    private void popInline(String type) {
        for (int i = inlineStack.size() - 1; i >= 0; i--) {
            Inline in = inlineStack.get(i);
            if (in.type.equals(type)) {
                inlineStack.remove(i);
                applyInline(in, out.length());
                return;
            }
        }
    }

    private void closeAllInline() {
        while (!inlineStack.isEmpty()) {
            Inline in = inlineStack.remove(inlineStack.size() - 1);
            applyInline(in, out.length());
        }
    }

    private void applyInline(Inline in, int end) {
        if (end <= in.start) return;
        if (in.type.equals("b")) {
            out.setSpan(new StyleSpan(Typeface.BOLD), in.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (in.type.equals("i")) {
            out.setSpan(new StyleSpan(Typeface.ITALIC), in.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (in.type.equals("a")) {
            if (in.href != null && in.href.length() > 0) {
                out.setSpan(new LinkSpan(decodeEntities(in.href)), in.start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    // ---------------- helpers ----------------

    private static boolean isAdDiv(String attrsLow) {
        return attrsLow.contains("adv")
                || attrsLow.contains("data-type=\"ads\"")
                || attrsLow.contains("data-type='ads'")
                || attrsLow.contains("id=\"ads_")
                || attrsLow.contains("id='ads_")
                || attrsLow.contains("zbads");
    }

    private static int indexOfSpace(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') return i;
        }
        return -1;
    }

    /** Extract an attribute value (double-quoted, single-quoted, or bare). */
    static String attrValue(String attrs, String name) {
        String low = attrs.toLowerCase(Locale.US);
        String key = name.toLowerCase(Locale.US) + "=";
        int i = low.indexOf(key);
        if (i < 0) return "";
        int j = i + key.length();
        if (j >= attrs.length()) return "";
        char q = attrs.charAt(j);
        if (q == '"' || q == '\'') {
            int k = attrs.indexOf(q, j + 1);
            if (k < 0) return "";
            return attrs.substring(j + 1, k);
        }
        int k = j;
        while (k < attrs.length()) {
            char c = attrs.charAt(k);
            if (c == ' ' || c == '\t' || c == '>') break;
            k++;
        }
        return attrs.substring(j, k);
    }

    /** Decode the common HTML entities plus numeric character references. */
    static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c != '&') {
                sb.append(c);
                i++;
                continue;
            }
            int semi = s.indexOf(';', i);
            if (semi < 0 || semi - i > 10) {
                sb.append(c);
                i++;
                continue;
            }
            String ent = s.substring(i + 1, semi);
            String rep = null;
            if (ent.startsWith("#x") || ent.startsWith("#X")) {
                try {
                    rep = String.valueOf((char) Integer.parseInt(ent.substring(2), 16));
                } catch (NumberFormatException ignored) {
                }
            } else if (ent.startsWith("#")) {
                try {
                    rep = String.valueOf((char) Integer.parseInt(ent.substring(1)));
                } catch (NumberFormatException ignored) {
                }
            } else if (ent.equals("amp")) rep = "&";
            else if (ent.equals("lt")) rep = "<";
            else if (ent.equals("gt")) rep = ">";
            else if (ent.equals("quot")) rep = "\"";
            else if (ent.equals("apos")) rep = "'";
            else if (ent.equals("nbsp")) rep = " ";
            else if (ent.equals("hellip")) rep = "…";
            else if (ent.equals("mdash")) rep = "—";
            else if (ent.equals("ndash")) rep = "–";
            else if (ent.equals("ldquo")) rep = "“";
            else if (ent.equals("rdquo")) rep = "”";
            else if (ent.equals("lsquo")) rep = "‘";
            else if (ent.equals("rsquo")) rep = "’";
            if (rep != null) {
                sb.append(rep);
                i = semi + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
