package com.tink.zaobaoeink;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ClickableSpan;
import android.text.style.DynamicDrawableSpan;
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
 * figure/img positions, bold/italic inline styles and <a href> links (LinkSpan).
 * With images off an img is a tappable "[图片]" placeholder paragraph; with
 * images on it is a fixed-height BodyImageSpan box in the flow (bitmap swapped
 * in asynchronously, box size never changes so pagination stays stable).
 * Ad divs are dropped; unknown tags degrade to plain paragraph text.
 * Whitespace-only text between blocks never starts a paragraph, paragraphs
 * with no visible text are dropped, and blank lines are capped at one.
 * No third-party parser.
 */
public final class BodyParser {

    /** Visual size (dp) of the zoom badge drawn in an image box's top-right corner. */
    static final float BADGE_DP = 40f;
    /** Tap target size (dp) for the badge; slightly larger than the drawing. */
    static final float BADGE_HIT_DP = 48f;

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

    /**
     * An inline body image (images enabled): one object-replacement char whose
     * replacement span reserves a fixed box in the text flow. The box size is
     * decided at parse time and never changes, so pagination computed before
     * the bitmap arrives stays valid after the bitmap is swapped in.
     */
    public static class BodyImageSpan extends DynamicDrawableSpan {
        public final String url;
        public final int boxW;
        public final int boxH;
        /** Set by the loader trigger so each span is fetched at most once. */
        public boolean requested;
        private final BoxDrawable drawable;

        public BodyImageSpan(String url, int boxW, int boxH, float lineMult, int badgePx) {
            super(ALIGN_BOTTOM);
            this.url = url;
            this.boxW = boxW;
            this.boxH = boxH;
            this.drawable = new BoxDrawable(boxW, boxH, lineMult, badgePx);
        }

        @Override
        public Drawable getDrawable() {
            return drawable;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            if (fm != null) {
                // The layout multiplies the line height by the article's
                // line-spacing multiplier; the drawable pre-divides by it, so
                // the final line height is exactly boxH, bitmap or not.
                int h = drawable.lineH;
                fm.ascent = -h;
                fm.descent = 0;
                fm.top = fm.ascent;
                fm.bottom = 0;
            }
            return boxW;
        }

        public void setBitmap(Bitmap b) {
            drawable.bitmap = b;
        }

        public boolean hasBitmap() {
            return drawable.bitmap != null;
        }
    }

    /** Fixed-bounds drawable: placeholder box first, fitted bitmap later. */
    static final class BoxDrawable extends Drawable {
        /** Height the layout reserves: boxH pre-divided by the line multiplier. */
        final int lineH;
        /** Zoom badge size in px (drawn in the box's top-right corner). */
        final int badgePx;
        Bitmap bitmap;

        BoxDrawable(int w, int h, float lineMult, int badgePx) {
            this.lineH = Math.max(1, (int) (h / (lineMult > 0 ? lineMult : 1f)));
            this.badgePx = badgePx;
            setBounds(0, 0, w, h);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (bitmap != null && bitmap.getWidth() > 0 && bitmap.getHeight() > 0) {
                float scale = Math.min((float) b.width() / bitmap.getWidth(),
                        (float) b.height() / bitmap.getHeight());
                int dw = Math.max(1, (int) (bitmap.getWidth() * scale));
                int dh = Math.max(1, (int) (bitmap.getHeight() * scale));
                int left = b.left + (b.width() - dw) / 2;
                int top = b.top + (b.height() - dh) / 2;
                canvas.drawBitmap(bitmap, null, new Rect(left, top, left + dw, top + dh), null);
            } else {
                Paint p = new Paint();
                p.setColor(Color.rgb(0xEE, 0xEE, 0xEE));
                p.setStyle(Paint.Style.FILL);
                canvas.drawRect(b, p);
                p.setColor(Color.rgb(0x66, 0x66, 0x66));
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2f);
                canvas.drawRect(b.left + 1, b.top + 1, b.right - 1, b.bottom - 1, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.rgb(0x44, 0x44, 0x44));
                float ts = Math.min(42f, b.height() / 6f);
                p.setTextSize(ts);
                p.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("[图片]", b.centerX(), b.centerY() + ts / 3f, p);
            }
            drawBadge(canvas, b);
        }

        /**
         * The only tap-to-zoom entry on the box (v1.6): a white square with a
         * black border and a magnifier-plus glyph, pinned to the top-right
         * corner. ArticleActivity hit-tests against this same corner.
         */
        private void drawBadge(Canvas canvas, Rect b) {
            int s = Math.min(badgePx, Math.min(b.width(), b.height()));
            if (s < 16) return;
            int l = b.right - s;
            int t = b.top;
            Paint p = new Paint();
            p.setAntiAlias(false);
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.FILL);
            canvas.drawRect(l, t, b.right, t + s, p);
            p.setColor(Color.BLACK);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, s * 0.045f));
            canvas.drawRect(l + 1, t + 1, b.right - 1, t + s - 1, p);
            float cx = l + s * 0.42f;
            float cy = t + s * 0.42f;
            float r = s * 0.20f;
            canvas.drawCircle(cx, cy, r, p);
            canvas.drawLine(cx + r * 0.72f, cy + r * 0.72f, l + s * 0.84f, t + s * 0.84f, p);
            float pl = s * 0.09f;
            canvas.drawLine(cx - pl, cy, cx + pl, cy, p);
            canvas.drawLine(cx, cy - pl, cx, cy + pl, p);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
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
    private final boolean imagesOn;
    private final int textWidthPx;
    private final float lineMult;
    private final int badgePx;

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

    private BodyParser(int bodyPx, float density, boolean imagesOn, int textWidthPx,
                       float lineMult) {
        this.bodyPx = bodyPx;
        this.h2Px = (int) (bodyPx * 1.30f);
        this.h3Px = (int) (bodyPx * 1.15f);
        this.captionPx = Math.max(10, (int) (bodyPx * 0.82f));
        this.marginPx = (int) (26 * density + 0.5f);
        this.imagesOn = imagesOn;
        this.textWidthPx = textWidthPx;
        this.lineMult = lineMult > 0 ? lineMult : 1f;
        this.badgePx = Math.max(24, (int) (BADGE_DP * density + 0.5f));
    }

    public static SpannableStringBuilder parse(String html, int bodyPx, float density,
                                               boolean imagesOn, int textWidthPx,
                                               float lineMult) {
        BodyParser p = new BodyParser(bodyPx, density, imagesOn, textWidthPx, lineMult);
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
        collapseBlankLines();
    }

    /** Cap consecutive newlines at one blank line; strip leading/trailing ones. */
    private void collapseBlankLines() {
        int i = 0;
        while (i < out.length()) {
            if (out.charAt(i) != '\n') {
                i++;
                continue;
            }
            int j = i;
            while (j < out.length() && out.charAt(j) == '\n') j++;
            int keep = (i == 0 || j == out.length()) ? 0 : Math.min(j - i, 2);
            if (j - i > keep) out.delete(i + keep, j);
            i += keep;
        }
    }

    private void onText(String raw) {
        if (skipDepth > 0 || skipTag != null) return;
        String t = decodeEntities(raw).replaceAll("\\s+", " ");
        if (t.length() == 0) return;
        if (paraStart < 0) {
            // Whitespace-only text between blocks (HTML source formatting,
            // &nbsp;-only nodes) must not open a phantom paragraph that would
            // render as a blank line.
            if (t.trim().length() == 0) return;
            startParagraph(inQuote ? P_QUOTE : P_NORMAL);
        }
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
                addImage(src, attrs);
            }
            return;
        }
        if (name.equals("br")) {
            // A <br> only breaks when the paragraph already has visible text,
            // and consecutive <br>s collapse into a single break.
            if (paraStart >= 0 && rangeHasVisible(paraStart, out.length())
                    && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
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

    /** True if [from, to) holds anything but spaces / indents / the item bullet. */
    private boolean rangeHasVisible(int from, int to) {
        for (int i = from; i < to; i++) {
            char c = out.charAt(i);
            if (c != ' ' && c != '　' && c != '\n' && c != '•') return true;
        }
        return false;
    }

    private void closeParagraph() {
        if (paraStart < 0) return;
        int end = out.length();
        // Trim trailing spaces/newlines belonging to this paragraph (keep structure tight).
        while (end > paraStart && (out.charAt(end - 1) == ' ' || out.charAt(end - 1) == '\n')) end--;
        if (!rangeHasVisible(paraStart, end)) {
            // No visible text (only the auto indent / bullet prefix): drop the
            // whole paragraph so it cannot render as a blank line.
            out.delete(paraStart, out.length());
            paraStart = -1;
            return;
        }
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

    private void addImage(String url, String attrs) {
        if (!imagesOn || textWidthPx <= 0 || url == null || url.length() == 0) {
            addImagePlaceholder(url);
            return;
        }
        String cleanUrl = decodeEntities(url);
        closeParagraph();
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
        int start = out.length();
        out.append('\uFFFC'); // object replacement char: the image box
        int end = start + 1;
        out.setSpan(new BodyImageSpan(cleanUrl, textWidthPx,
                        imageBoxHeight(attrs, textWidthPx), lineMult, badgePx),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        // Same range also carries the tappable span (opens the full-screen viewer).
        out.setSpan(new ImageSpan(cleanUrl), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append('\n');
        paraStart = -1;
    }

    /**
     * Append a standalone image box to an already-built document: the same
     * 3:2 box, zoom badge and tap spans as an in-body {@code <img>} without
     * width/height attributes. Used for the article cover image, which lives
     * in the detail payload's images array rather than in the body HTML.
     * The caller guarantees images are enabled and textWidthPx > 0.
     */
    public static void appendImageBox(SpannableStringBuilder out, String url,
                                      float density, int textWidthPx, float lineMult) {
        if (url == null || url.length() == 0 || textWidthPx <= 0) return;
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
        int badgePx = Math.max(24, (int) (BADGE_DP * density + 0.5f));
        int h = textWidthPx * 2 / 3;
        if (h < textWidthPx / 4) h = textWidthPx / 4;
        if (h > textWidthPx) h = textWidthPx;
        int start = out.length();
        out.append('\uFFFC'); // object replacement char: the image box
        int end = start + 1;
        out.setSpan(new BodyImageSpan(url, textWidthPx, h,
                        lineMult > 0 ? lineMult : 1f, badgePx),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        // Same range also carries the tappable span (opens the full-screen viewer).
        out.setSpan(new ImageSpan(url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append('\n');
    }

    /**
     * Box height for an inline image: from the img width/height attributes when
     * both are present, else a fixed 3:2 box. Clamped to [w/4, w] so a single
     * image line can never exceed a page. Fixed at parse time — the loaded
     * bitmap is fitted inside the box, never resizing it.
     */
    private int imageBoxHeight(String attrs, int w) {
        int aw = parseDim(attrValue(attrs, "width"));
        int ah = parseDim(attrValue(attrs, "height"));
        int h = (aw > 0 && ah > 0) ? (int) ((long) w * ah / aw) : w * 2 / 3;
        if (h < w / 4) h = w / 4;
        if (h > w) h = w;
        return h;
    }

    /** Leading integer of an attribute value ("640", "640px" -> 640; else 0). */
    private static int parseDim(String v) {
        if (v == null) return 0;
        int n = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') break;
            n = n * 10 + (c - '0');
        }
        return n;
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
        // Empty-paragraph deletion may have shifted the tail under a still-open
        // inline element; clamp instead of setting an out-of-range span.
        if (in.start >= out.length()) return;
        if (end > out.length()) end = out.length();
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
