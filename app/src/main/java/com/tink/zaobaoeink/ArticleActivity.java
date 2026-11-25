package com.tink.zaobaoeink;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Article page: the whole document is one Spanned text (title/meta/preview +
 * structured body from BodyParser). Pages are pre-sliced with StaticLayout on the
 * background executor; a page turn only swaps the displayed slice.
 * Taps are resolved manually: link spans first, then the zoom badge on inline
 * images (v1.6: the image box itself no longer zooms — taps on it page like
 * anywhere else), left/right half paging last.
 * Article-to-article jumps (related list, in-body story links) replace this
 * activity instead of stacking, so Back always lands on the article list.
 */
public class ArticleActivity extends Activity {

    private static final Pattern STORY_ID = Pattern.compile("story\\d{8}-(\\d+)");
    private static final float LINE_MULT = 1.45f;

    private String documentId;

    private TextView bodyView;
    private TextView pageInfo;
    private Button imgBtn;
    private ListView relatedList;
    private LinearLayout statusBox;
    private TextView statusText;
    private Button statusRetry;

    private SpannableStringBuilder doc;
    private List<Models.Card> related = new ArrayList<Models.Card>();
    private RelatedAdapter relatedAdapter;
    private List<int[]> pages;
    private int pageIdx = 0;
    private boolean relatedMode = false;
    private boolean fetching = false;
    /** Text-area size the current pages were sliced for; -1 until first paginate. */
    private int pagedW = -1;
    private int pagedH = -1;

    /** Raw pieces kept so the document can be rebuilt when the font size changes. */
    private static final class Parts {
        String headline = "";
        String meta = "";
        boolean paid;
        String preview = "";
        String bodyHtml = "";
        /** Cover image (detail data.images[0]); empty when the payload has none. */
        String coverUrl = "";
        /** Every rendition URL of images[0], for cover-vs-body dedupe. */
        final List<String> coverSpecs = new ArrayList<String>();
    }

    private Parts parts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Api.init(this);
        documentId = getIntent().getStringExtra("documentId");
        if (documentId == null) documentId = "";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // ---- top bar ----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 8);
        bar.setPadding(pad, pad, pad, pad);

        Button backBtn = Ui.button(this, "返回");
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(backBtn);

        pageInfo = Ui.text(this, "", 15, false);
        pageInfo.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams piLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        piLp.leftMargin = Ui.dp(this, 6);
        piLp.rightMargin = Ui.dp(this, 6);
        pageInfo.setLayoutParams(piLp);
        bar.addView(pageInfo);

        Button minusBtn = Ui.button(this, "A-");
        minusBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeFont(-1);
            }
        });
        bar.addView(minusBtn);
        Button plusBtn = Ui.button(this, "A+");
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.leftMargin = Ui.dp(this, 6);
        plusBtn.setLayoutParams(pLp);
        plusBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeFont(1);
            }
        });
        bar.addView(plusBtn);
        Button refreshBtn = Ui.button(this, "刷新");
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.leftMargin = Ui.dp(this, 6);
        refreshBtn.setLayoutParams(rLp);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fetchArticle();
            }
        });
        bar.addView(refreshBtn);
        imgBtn = Ui.button(this, Prefs.imagesEnabled(this) ? "图片：开" : "图片：关");
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        iLp.leftMargin = Ui.dp(this, 6);
        imgBtn.setLayoutParams(iLp);
        imgBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleImages();
            }
        });
        bar.addView(imgBtn);
        root.addView(bar);

        View div = Ui.divider(this);
        root.addView(div, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 2)));

        // ---- content area ----
        FrameLayout content = new FrameLayout(this);

        bodyView = new TextView(this);
        bodyView.setTextColor(Color.BLACK);
        bodyView.setBackgroundColor(Color.WHITE);
        int bp = Ui.dp(this, 16);
        bodyView.setPadding(bp, Ui.dp(this, 12), bp, Ui.dp(this, 12));
        bodyView.setLineSpacing(0, LINE_MULT);
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                Prefs.FONT_SIZES_SP[Prefs.fontLevel(this)]);
        bodyView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    handleTap(event.getX(), event.getY());
                }
                return true;
            }
        });
        // If the text area settles to a different size after the pages were
        // sliced (first layout pass, system bars appearing, rotation), the
        // slices no longer match the view: re-slice for the real size,
        // keeping the reader on the same text offset.
        bodyView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (doc == null || pages == null || pages.isEmpty()) return;
                int w = (right - left) - bodyView.getPaddingLeft() - bodyView.getPaddingRight();
                int h = (bottom - top) - bodyView.getPaddingTop() - bodyView.getPaddingBottom();
                if (w > 0 && h > 0 && (w != pagedW || h != pagedH)) {
                    paginate(pages.get(pageIdx)[0]);
                }
            }
        });
        content.addView(bodyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        relatedList = new ListView(this);
        relatedList.setBackgroundColor(Color.WHITE);
        relatedList.setDivider(new android.graphics.drawable.ColorDrawable(
                Color.rgb(0x99, 0x99, 0x99)));
        relatedList.setDividerHeight(1);
        relatedAdapter = new RelatedAdapter();
        relatedList.setAdapter(relatedAdapter);
        relatedList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Models.Card c = related.get(position);
                Intent it = new Intent(ArticleActivity.this, ArticleActivity.class);
                it.putExtra("documentId", c.documentId);
                it.putExtra("headline", c.headline);
                startActivity(it);
                // Replace, don't stack: however deep the reader hops through
                // related articles, Back must land straight on the list.
                finish();
            }
        });
        relatedList.setVisibility(View.GONE);
        content.addView(relatedList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusBox.setGravity(Gravity.CENTER);
        statusBox.setBackgroundColor(Color.WHITE);
        statusBox.setPadding(bp, bp, bp, bp);
        statusText = Ui.text(this, "", 16, false);
        statusText.setGravity(Gravity.CENTER);
        statusBox.addView(statusText);
        statusRetry = Ui.button(this, "重试");
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = Ui.dp(this, 14);
        statusRetry.setLayoutParams(sLp);
        statusRetry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fetchArticle();
            }
        });
        statusBox.addView(statusRetry);
        statusBox.setVisibility(View.GONE);
        content.addView(statusBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);

        String cached = Cache.read(this, Cache.articleKey(documentId));
        if (cached != null) {
            renderRaw(cached, 0);
        } else {
            showStatus("正在加载文章…", false);
            fetchArticle();
        }
    }

    // ---------------- loading & rendering ----------------

    private void fetchArticle() {
        if (fetching) return;
        fetching = true;
        Bg.run(new Bg.Work<String>() {
            @Override
            public String run() throws Exception {
                return Api.fetchRaw(Api.articleUrl(documentId));
            }
        }, new Bg.Done<String>() {
            @Override
            public void onDone(String raw, Exception error) {
                fetching = false;
                if (error == null && raw != null) {
                    Cache.write(ArticleActivity.this, Cache.articleKey(documentId), raw);
                    hideStatus();
                    renderRaw(raw, 0);
                } else if (doc != null) {
                    Toast.makeText(ArticleActivity.this, "刷新失败，显示已缓存内容",
                            Toast.LENGTH_SHORT).show();
                } else {
                    showStatus("文章加载失败，请检查网络后重试。", true);
                }
            }
        });
    }

    /** Parse the detail JSON and build the document (heavy work off the UI thread). */
    private void renderRaw(final String raw, final int keepOffset) {
        final int fontSp = Prefs.FONT_SIZES_SP[Prefs.fontLevel(this)];
        final float density = getResources().getDisplayMetrics().density;
        Bg.run(new Bg.Work<Object[]>() {
            @Override
            public Object[] run() throws Exception {
                JSONObject root = new JSONObject(raw);
                JSONObject d = root.optJSONObject("data");
                if (d == null) throw new Exception("bad payload");
                Parts p = new Parts();
                p.headline = d.optString("headline", "");
                p.paid = d.optBoolean("paid", false);
                StringBuilder meta = new StringBuilder();
                JSONObject sec = d.optJSONObject("section");
                if (sec != null) meta.append(sec.optString("name", ""));
                JSONArray authors = d.optJSONArray("authors");
                if (authors != null && authors.length() > 0) {
                    StringBuilder names = new StringBuilder();
                    for (int i = 0; i < authors.length(); i++) {
                        JSONObject a = authors.optJSONObject(i);
                        if (a == null) continue;
                        if (names.length() > 0) names.append("、");
                        names.append(a.optString("name", ""));
                    }
                    if (names.length() > 0) {
                        if (meta.length() > 0) meta.append(" · ");
                        meta.append(names);
                    }
                }
                String time = Ui.formatTime(d.optLong("publicationTime", 0));
                if (time.length() > 0) {
                    if (meta.length() > 0) meta.append(" · ");
                    meta.append(time);
                }
                p.meta = meta.toString();
                p.preview = d.optString("contentPreview", "").trim();
                p.bodyHtml = d.optString("body", "");

                // Cover/hero image: data.images[0] — the same photo the list
                // rows show as a thumbnail. It never appears in the body HTML
                // (the body's own <img>s are different pictures), so the
                // article page has to place it explicitly (buildDoc).
                // Spec choice: "url" is the full-resolution original, the
                // same kind of source the body <img> pipeline consumes and
                // downsamples via Images.loadArticleImage; "large" is a
                // 1140px-wide rendition, narrower than the article column
                // on the XF-A1 (~1250px) and would be upscaled blurry.
                JSONArray imgs = d.optJSONArray("images");
                if (imgs != null && imgs.length() > 0) {
                    JSONObject im = imgs.optJSONObject(0);
                    if (im != null) {
                        p.coverUrl = im.optString("url", "");
                        if (p.coverUrl.length() == 0) p.coverUrl = im.optString("large", "");
                        String[] specKeys = {"url", "large", "fullPath",
                                "thumbnail", "rotator", "square"};
                        for (String k : specKeys) {
                            String v = im.optString(k, "");
                            if (v.length() > 0) p.coverSpecs.add(v);
                        }
                    }
                }

                List<Models.Card> rel = new ArrayList<Models.Card>();
                JSONArray relArr = d.optJSONArray("related");
                if (relArr != null) {
                    for (int i = 0; i < relArr.length(); i++) {
                        JSONObject o = relArr.optJSONObject(i);
                        if (o == null) continue;
                        Models.Card c = Models.toCard(o);
                        // v1.5: related items show exactly as returned, paid
                        // and free mixed, no filter and no badge (the v1.3
                        // paid filter here was dropped by user decision).
                        if (c != null) rel.add(c);
                    }
                }
                SpannableStringBuilder built = buildDoc(p, fontSp, density);
                return new Object[]{p, built, rel};
            }
        }, new Bg.Done<Object[]>() {
            @Override
            public void onDone(Object[] result, Exception error) {
                if (error != null || result == null) {
                    if (doc == null) showStatus("文章内容解析失败。", true);
                    return;
                }
                parts = (Parts) result[0];
                doc = (SpannableStringBuilder) result[1];
                @SuppressWarnings("unchecked")
                List<Models.Card> rel = (List<Models.Card>) result[2];
                related = rel;
                relatedAdapter.notifyDataSetChanged();
                hideStatus();
                paginate(keepOffset);
            }
        });
    }

    private SpannableStringBuilder buildDoc(Parts p, int fontSp, float density) {
        int bodyPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSp,
                getResources().getDisplayMetrics());
        SpannableStringBuilder sb = new SpannableStringBuilder();

        int hs = sb.length();
        sb.append(p.headline);
        sb.setSpan(new StyleSpan(Typeface.BOLD), hs, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new AbsoluteSizeSpan((int) (bodyPx * 1.35f)), hs, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append('\n');

        int ms = sb.length();
        sb.append(p.meta);
        sb.setSpan(new AbsoluteSizeSpan((int) (bodyPx * 0.78f)), ms, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(Ui.GREY), ms, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append('\n').append('\n');

        boolean imagesOn = Prefs.imagesEnabled(this);
        int tw = textWidthPx();
        SpannableStringBuilder body = BodyParser.parse(p.bodyHtml, bodyPx, density,
                imagesOn, tw, LINE_MULT);
        // Hero image (v1.7): the cover photo goes right under the meta line,
        // before the lead/body text, as the first element of the text flow —
        // same image box, grayscale loading, zoom badge and tap rules as an
        // in-body image. Skipped when images are off (no placeholder), when
        // the payload has no cover, or when the body already shows the same
        // file. The caption is not rendered.
        if (imagesOn && p.coverUrl.length() > 0 && !coverInBody(p, body)) {
            BodyParser.appendImageBox(sb, p.coverUrl, density, tw, LINE_MULT);
        }
        if (p.preview.length() > 0) {
            String bodyText = body.toString();
            int nl = bodyText.indexOf('\n');
            String firstPara = (nl < 0 ? bodyText : bodyText.substring(0, nl))
                    .replace("　　", "").trim();
            if (!p.preview.equals(firstPara)) {
                int ps = sb.length();
                sb.append(p.preview);
                sb.setSpan(new StyleSpan(Typeface.ITALIC), ps, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new AbsoluteSizeSpan((int) (bodyPx * 0.92f)), ps, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(Color.rgb(0x33, 0x33, 0x33)), ps, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append('\n');
            }
        }
        sb.append(body);
        return sb;
    }

    /**
     * True when the parsed body already contains the cover image. Comparison
     * is by image-file identity: the URL path (which ends in the file's hash)
     * with the query string dropped and a trailing ".jpg" stripped — the
     * cover's renditions carry "?o=zbimg…" queries and a .jpg suffix, while
     * body {@code <img>} URLs are bare paths. Every rendition of images[0]
     * counts as the cover side; any body ImageSpan URL matching one of them
     * means the same file is already in the flow.
     */
    private static boolean coverInBody(Parts p, SpannableStringBuilder body) {
        if (p.coverSpecs.isEmpty()) return false;
        java.util.HashSet<String> cover = new java.util.HashSet<String>();
        for (String s : p.coverSpecs) cover.add(imageFileKey(s));
        BodyParser.ImageSpan[] spans = body.getSpans(0, body.length(),
                BodyParser.ImageSpan.class);
        if (spans == null) return false;
        for (BodyParser.ImageSpan sp : spans) {
            if (cover.contains(imageFileKey(sp.url))) return true;
        }
        return false;
    }

    /** Image-file identity of a URL: full path minus query and ".jpg" suffix. */
    private static String imageFileKey(String url) {
        if (url == null) return "";
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        if (u.length() > 4 && u.substring(u.length() - 4).equalsIgnoreCase(".jpg")) {
            u = u.substring(0, u.length() - 4);
        }
        return u;
    }

    // ---------------- pagination ----------------

    private void paginate(final int keepOffset) {
        if (doc == null) return;
        final int w = bodyView.getWidth() - bodyView.getPaddingLeft() - bodyView.getPaddingRight();
        final int h = bodyView.getHeight() - bodyView.getPaddingTop() - bodyView.getPaddingBottom();
        if (w <= 0 || h <= 0) {
            bodyView.post(new Runnable() {
                @Override
                public void run() {
                    paginate(keepOffset);
                }
            });
            return;
        }
        pagedW = w;
        pagedH = h;
        final TextPaint paint = new TextPaint(bodyView.getPaint());
        // Page budget: the raw text height minus a safety margin. A displayed
        // page is re-laid-out by the TextView on its own, and that second pass
        // is systematically a little taller than this measuring pass over the
        // whole document (the slice re-adds the font's top/bottom padding at
        // both of its ends; fallback-font line spacing applies there too).
        // Budgeting the full height let a page measure as "fits" while the
        // TextView clipped its last line or two (the v1.5 "1/1 but cut off"
        // bug). Reserve the font padding plus one full line: a page may end
        // a line early, but content can never be silently clipped.
        Paint.FontMetricsInt fm = paint.getFontMetricsInt();
        int lineH = (int) Math.ceil((fm.descent - fm.ascent) * (double) LINE_MULT);
        int padExtra = Math.max(0, fm.top - fm.ascent) + Math.max(0, fm.bottom - fm.descent);
        int budget = h - padExtra - lineH;
        if (budget < lineH) budget = h; // degenerate tiny area: fall back to raw height
        final int pageBudget = budget;
        final SpannableStringBuilder text = doc;
        Bg.run(new Bg.Work<List<int[]>>() {
            @Override
            public List<int[]> run() {
                // Measure with the TextView's own layout parameters (same
                // width, same paint, same spacing) so line heights match what
                // the view will actually render: TextView defaults are
                // high-quality breaking, normal hyphenation, and — since
                // API 28 — fallback-font line spacing, which a bare
                // StaticLayout.Builder leaves off and under-measures
                // mixed CJK/Latin lines.
                StaticLayout.Builder lb = StaticLayout.Builder
                        .obtain(text, 0, text.length(), paint, w)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(0, LINE_MULT)
                        .setIncludePad(true)
                        .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
                if (Build.VERSION.SDK_INT >= 28) {
                    lb.setUseLineSpacingFromFallbacks(true);
                }
                StaticLayout layout = lb.build();
                int lines = layout.getLineCount();
                int[] starts = new int[lines];
                int[] ends = new int[lines];
                int[] tops = new int[lines];
                int[] bottoms = new int[lines];
                for (int i = 0; i < lines; i++) {
                    starts[i] = layout.getLineStart(i);
                    ends[i] = layout.getLineEnd(i);
                    tops[i] = layout.getLineTop(i);
                    bottoms[i] = layout.getLineBottom(i);
                }
                return Paginator.slice(starts, ends, tops, bottoms, pageBudget,
                        text.length());
            }
        }, new Bg.Done<List<int[]>>() {
            @Override
            public void onDone(List<int[]> result, Exception error) {
                if (error != null || result == null || doc != text) return;
                pages = result;
                int idx = 0;
                if (keepOffset > 0) {
                    for (int i = 0; i < pages.size(); i++) {
                        if (keepOffset >= pages.get(i)[0] && keepOffset < pages.get(i)[1]) {
                            idx = i;
                            break;
                        }
                        if (keepOffset >= pages.get(i)[0]) idx = i;
                    }
                }
                showPage(idx);
                ensureArticleImages();
            }
        });
    }

    private void showPage(int idx) {
        if (pages == null || pages.isEmpty()) return;
        if (idx < 0) idx = 0;
        if (idx >= pages.size()) idx = pages.size() - 1;
        pageIdx = idx;
        relatedMode = false;
        relatedList.setVisibility(View.GONE);
        bodyView.setVisibility(View.VISIBLE);
        int s = pages.get(idx)[0];
        int e = pages.get(idx)[1];
        bodyView.setText(doc.subSequence(s, e));
        pageInfo.setText((idx + 1) + " / " + pages.size());
    }

    private void showRelated() {
        relatedMode = true;
        bodyView.setVisibility(View.GONE);
        relatedList.setVisibility(View.VISIBLE);
        pageInfo.setText("相关推荐");
    }

    private void nextPage() {
        if (relatedMode || pages == null) return;
        if (pageIdx < pages.size() - 1) {
            showPage(pageIdx + 1);
        } else if (!related.isEmpty()) {
            showRelated();
        }
    }

    private void prevPage() {
        if (relatedMode) {
            if (pages != null && !pages.isEmpty()) showPage(pages.size() - 1);
            return;
        }
        if (pages != null && pageIdx > 0) showPage(pageIdx - 1);
    }

    private void changeFont(int delta) {
        int level = Prefs.fontLevel(this);
        int next = level + delta;
        if (next < 0 || next >= Prefs.FONT_SIZES_SP.length || next == level) return;
        Prefs.setFontLevel(this, next);
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, Prefs.FONT_SIZES_SP[next]);
        if (parts != null) {
            int keep = (pages != null && !pages.isEmpty()) ? pages.get(pageIdx)[0] : 0;
            float density = getResources().getDisplayMetrics().density;
            doc = buildDoc(parts, Prefs.FONT_SIZES_SP[next], density);
            paginate(keep);
        }
    }

    // ---------------- inline body images ----------------

    /** Width available to body text (the article view spans the full screen). */
    private int textWidthPx() {
        int w = bodyView.getWidth();
        if (w <= 0) w = getResources().getDisplayMetrics().widthPixels;
        return w - bodyView.getPaddingLeft() - bodyView.getPaddingRight();
    }

    private void toggleImages() {
        boolean on = !Prefs.imagesEnabled(this);
        Prefs.setImagesEnabled(this, on);
        imgBtn.setText(on ? "图片：开" : "图片：关");
        Toast.makeText(this, on ? "正文图片已开启" : "正文图片已关闭",
                Toast.LENGTH_SHORT).show();
        if (parts != null) {
            int keep = (pages != null && !pages.isEmpty()) ? pages.get(pageIdx)[0] : 0;
            float density = getResources().getDisplayMetrics().density;
            doc = buildDoc(parts, Prefs.FONT_SIZES_SP[Prefs.fontLevel(this)], density);
            paginate(keep);
        }
    }

    /**
     * Fetch bitmaps for the doc's inline image boxes (once per span). Box sizes
     * are fixed at parse time, so a bitmap arriving only repaints — it can
     * never shift the pagination.
     */
    private void ensureArticleImages() {
        if (doc == null || !Prefs.imagesEnabled(this)) return;
        BodyParser.BodyImageSpan[] spans = doc.getSpans(0, doc.length(),
                BodyParser.BodyImageSpan.class);
        if (spans == null || spans.length == 0) return;
        final List<BodyParser.BodyImageSpan> todo =
                new ArrayList<BodyParser.BodyImageSpan>();
        for (BodyParser.BodyImageSpan s : spans) {
            if (!s.requested && !s.hasBitmap()) {
                s.requested = true;
                todo.add(s);
            }
        }
        if (todo.isEmpty()) return;
        final SpannableStringBuilder builtFor = doc;
        Bg.run(new Bg.Work<List<Object[]>>() {
            @Override
            public List<Object[]> run() {
                List<Object[]> out = new ArrayList<Object[]>();
                for (BodyParser.BodyImageSpan s : todo) {
                    Bitmap bmp = Images.loadArticleImage(s.url, s.boxW);
                    if (bmp != null) out.add(new Object[]{s, bmp});
                }
                return out;
            }
        }, new Bg.Done<List<Object[]>>() {
            @Override
            public void onDone(List<Object[]> result, Exception error) {
                if (result == null || doc != builtFor) return;
                boolean any = false;
                for (Object[] pair : result) {
                    ((BodyParser.BodyImageSpan) pair[0]).setBitmap((Bitmap) pair[1]);
                    any = true;
                }
                if (any) bodyView.invalidate();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The same switch lives on the home screen; keep this label in sync.
        if (imgBtn != null) {
            imgBtn.setText(Prefs.imagesEnabled(this) ? "图片：开" : "图片：关");
        }
    }

    // ---------------- tap handling: spans first, paging second ----------------

    private void handleTap(float x, float y) {
        if (doc == null || pages == null || relatedMode) return;
        int width = bodyView.getWidth();
        Layout layout = bodyView.getLayout();
        if (layout != null) {
            int lx = (int) x - bodyView.getTotalPaddingLeft() + bodyView.getScrollX();
            int ly = (int) y - bodyView.getTotalPaddingTop() + bodyView.getScrollY();
            int line = layout.getLineForVertical(ly);
            int localOff = layout.getOffsetForHorizontal(line, lx);
            int globalOff = pages.get(pageIdx)[0] + localOff;
            ClickableSpan hit = findSpan(globalOff, BodyParser.LinkSpan.class);
            if (hit == null) hit = findSpan(globalOff, BodyParser.ImageSpan.class);
            if (hit instanceof BodyParser.LinkSpan) {
                openLink(((BodyParser.LinkSpan) hit).url);
                return;
            }
            if (hit instanceof BodyParser.ImageSpan) {
                BodyParser.BodyImageSpan box = findBodyImage((BodyParser.ImageSpan) hit);
                if (box != null) {
                    // Inline image box (images on): the box itself must not
                    // swallow taps into a zoom — on an e-ink screen that was
                    // a constant mis-tap. Only the badge drawn in the box's
                    // top-right corner opens the viewer; a tap anywhere else
                    // on the box pages like a tap on plain text.
                    if (tapOnBadge(x, y, layout, box)) {
                        Intent it = new Intent(this, ImageViewerActivity.class);
                        it.putExtra(ImageViewerActivity.EXTRA_URL,
                                ((BodyParser.ImageSpan) hit).url);
                        startActivity(it);
                    } else if (x < width / 2f) {
                        prevPage();
                    } else {
                        nextPage();
                    }
                } else if (Prefs.imagesEnabled(this)) {
                    // "[图片]" placeholder text (no inline box): old behavior.
                    Intent it = new Intent(this, ImageViewerActivity.class);
                    it.putExtra(ImageViewerActivity.EXTRA_URL, ((BodyParser.ImageSpan) hit).url);
                    startActivity(it);
                }
                return;
            }
        }
        if (x < width / 2f) {
            prevPage();
        } else {
            nextPage();
        }
    }

    /** The inline image box sharing the tapped ImageSpan's range, if any. */
    private BodyParser.BodyImageSpan findBodyImage(BodyParser.ImageSpan img) {
        if (doc == null) return null;
        int s = doc.getSpanStart(img);
        int e = doc.getSpanEnd(img);
        if (s < 0 || e <= s) return null;
        BodyParser.BodyImageSpan[] arr = doc.getSpans(s, e, BodyParser.BodyImageSpan.class);
        return (arr != null && arr.length > 0) ? arr[0] : null;
    }

    /**
     * True when the tap lands on the zoom badge pinned to the image box's
     * top-right corner. The box's rect is recovered from the current page
     * layout: the image char sits alone on its line at the line's start, and
     * the drawable fills the line exactly (see BodyImageSpan.getSize).
     * The hit square (48dp) is a bit larger than the drawn badge (40dp).
     */
    private boolean tapOnBadge(float x, float y, Layout layout, BodyParser.BodyImageSpan box) {
        if (doc == null || pages == null) return false;
        int s = doc.getSpanStart(box);
        if (s < 0) return false;
        int local = s - pages.get(pageIdx)[0];
        if (local < 0) return false;
        int line = layout.getLineForOffset(local);
        int boxLeft = bodyView.getTotalPaddingLeft() - bodyView.getScrollX()
                + (int) layout.getPrimaryHorizontal(local);
        int boxTop = bodyView.getTotalPaddingTop() - bodyView.getScrollY()
                + layout.getLineTop(line);
        int hit = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                BodyParser.BADGE_HIT_DP, getResources().getDisplayMetrics());
        int slack = hit / 6;
        int right = boxLeft + box.boxW;
        return x >= right - hit && x < right + slack
                && y >= boxTop - slack && y < boxTop + hit;
    }

    private ClickableSpan findSpan(int off, Class<? extends ClickableSpan> type) {
        if (doc == null) return null;
        int from = Math.max(0, off - 1);
        int to = Math.min(doc.length(), off + 1);
        ClickableSpan[] spans = doc.getSpans(from, to, type);
        for (ClickableSpan s : spans) {
            int ss = doc.getSpanStart(s);
            int se = doc.getSpanEnd(s);
            if (off >= ss && off < se) return s;
        }
        for (ClickableSpan s : spans) {
            int ss = doc.getSpanStart(s);
            int se = doc.getSpanEnd(s);
            if (off - 1 >= ss && off - 1 < se) return s;
        }
        return null;
    }

    private void openLink(String rawUrl) {
        if (rawUrl == null) return;
        String u = rawUrl.trim();
        if (u.length() == 0) return;
        if (u.startsWith("//")) {
            u = "https:" + u;
        } else if (u.startsWith("/")) {
            u = "https://www.zaobao.com" + u;
        } else if (!u.contains("://")) {
            u = "https://www.zaobao.com/" + u;
        }
        if (u.contains("zaobao.com")) {
            Matcher m = STORY_ID.matcher(u);
            if (m.find()) {
                Intent it = new Intent(this, ArticleActivity.class);
                it.putExtra("documentId", m.group(1));
                it.putExtra("headline", "");
                startActivity(it);
                finish(); // replace, don't stack (same rule as related hops)
                return;
            }
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "没有可打开此链接的应用", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            prevPage();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            nextPage();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------- status ----------------

    private void showStatus(String msg, boolean withRetry) {
        statusText.setText(msg);
        statusRetry.setVisibility(withRetry ? View.VISIBLE : View.GONE);
        statusBox.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        statusBox.setVisibility(View.GONE);
    }

    // ---------------- related adapter ----------------

    private final class RelatedAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return related.size();
        }

        @Override
        public Object getItem(int position) {
            return related.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView headline;
            TextView meta;
            if (convertView == null) {
                row = new LinearLayout(ArticleActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(Ui.dp(ArticleActivity.this, 14), Ui.dp(ArticleActivity.this, 12),
                        Ui.dp(ArticleActivity.this, 14), Ui.dp(ArticleActivity.this, 12));
                headline = Ui.text(ArticleActivity.this, "", 16, false);
                headline.setTag("h");
                row.addView(headline);
                meta = Ui.text(ArticleActivity.this, "", 13, false);
                meta.setTextColor(Ui.GREY);
                meta.setTag("m");
                LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                mLp.topMargin = Ui.dp(ArticleActivity.this, 5);
                meta.setLayoutParams(mLp);
                row.addView(meta);
            } else {
                row = (LinearLayout) convertView;
                headline = (TextView) row.findViewWithTag("h");
                meta = (TextView) row.findViewWithTag("m");
            }
            Models.Card c = related.get(position);
            headline.setText(c.headline);
            StringBuilder mb = new StringBuilder();
            if (c.sectionName.length() > 0) mb.append(c.sectionName);
            String t = Ui.formatTime(c.pubTimeSec);
            if (t.length() > 0) {
                if (mb.length() > 0) mb.append(" · ");
                mb.append(t);
            }
            meta.setText(mb.toString());
            return row;
        }
    }
}
