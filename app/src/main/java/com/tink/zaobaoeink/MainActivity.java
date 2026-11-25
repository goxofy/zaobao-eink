package com.tink.zaobaoeink;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Home screen (v1.1): title bar + a horizontally scrollable section tab strip
 * (NetEase-style) + the selected section's article list below, all in one page.
 *
 * Tabs are exactly the menu sections, in menu order — no synthetic tabs.
 *
 * v1.5 display rules (user decision 2026-11-25, superseding the v1.2–v1.4
 * paid split and its separate 付费 tab): the homepage tab shows free articles
 * only — its feed is distilled from the sub-sections — and it keeps the v1.4
 * forward-scan batches (see scanSection) so a paid-heavy page cannot leave
 * it blank. Every other section (「最新」 included) shows paid and free cards
 * mixed in feed order, one source page at a time, with no filtering, no
 * re-sorting and no paid badges anywhere in the app.
 */
public class MainActivity extends Activity {

    private static final long MENU_TTL_MS = 24L * 3600 * 1000;
    /** Feed id of the homepage section — the only tab that filters paid cards. */
    private static final String HOMEPAGE_FEED = "homepage";

    // v1.4 forward-scan batches: visible-row targets and the per-batch page cap.
    private static final int FIRST_BATCH_TARGET = 10;
    private static final int NEXT_BATCH_TARGET = 8;
    private static final int MAX_SCAN_PAGES = 12;

    // ---------------- tab model ----------------

    private static final class TabItem {
        final Models.Section section;

        TabItem(Models.Section s) {
            section = s;
        }

        String key() {
            return section.feed;
        }

        String label() {
            return section.name;
        }
    }

    private final List<TabItem> tabs = new ArrayList<TabItem>();
    private final List<TextView> tabViews = new ArrayList<TextView>();
    private int currentTab = -1;
    private boolean tabsReady = false;
    private boolean fetchingMenu = false;

    // ---------------- list state (of the currently selected tab) ----------------

    private final List<Models.Card> rows = new ArrayList<Models.Card>();
    private final HashSet<String> seenIds = new HashSet<String>();
    private int page = 0;
    private boolean hasMore = false;
    private boolean loading = false;
    private int loadGen = 0;
    private boolean imagesOn = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private HorizontalScrollView tabScroll;
    private LinearLayout tabBar;
    private TextView statusView;
    private Button imgToggleBtn;
    private Button moreBtn;
    private ListView listView;
    private RowAdapter adapter;

    // ---------------- lifecycle / UI ----------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Api.init(this);
        imagesOn = Prefs.imagesEnabled(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 10);
        bar.setPadding(pad, pad, pad, pad);

        TextView title = Ui.text(this, "早报墨水版", 20, true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        imgToggleBtn = Ui.button(this, "");
        updateImgToggleLabel();
        imgToggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !Prefs.imagesEnabled(MainActivity.this);
                Prefs.setImagesEnabled(MainActivity.this, on);
                imagesOn = on;
                updateImgToggleLabel();
                adapter.notifyDataSetChanged();
                Toast.makeText(MainActivity.this, on ? "列表图片已开启" : "列表图片已关闭",
                        Toast.LENGTH_SHORT).show();
            }
        });
        bar.addView(imgToggleBtn);

        Button refreshBtn = Ui.button(this, "刷新");
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rbLp.leftMargin = Ui.dp(this, 8);
        refreshBtn.setLayoutParams(rbLp);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRefresh();
            }
        });
        bar.addView(refreshBtn);
        root.addView(bar);

        View div = Ui.divider(this);
        root.addView(div, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 2)));

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        tabBar.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 8));
        tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.addView(tabBar, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(tabScroll);

        View div2 = Ui.divider(this);
        root.addView(div2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 2)));

        statusView = Ui.text(this, "", 15, false);
        statusView.setTextColor(Ui.GREY);
        statusView.setPadding(pad, pad, pad, pad);
        statusView.setVisibility(View.GONE);
        root.addView(statusView);

        listView = new ListView(this);
        listView.setDivider(new android.graphics.drawable.ColorDrawable(Color.rgb(0x99, 0x99, 0x99)));
        listView.setDividerHeight(1);
        adapter = new RowAdapter();
        moreBtn = Ui.button(this, "加载下一页");
        moreBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadSectionNext();
            }
        });
        listView.addFooterView(moreBtn);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= rows.size()) return; // footer
                Models.Card c = rows.get(position);
                Intent it = new Intent(MainActivity.this, ArticleActivity.class);
                it.putExtra("documentId", c.documentId);
                it.putExtra("headline", c.headline);
                startActivity(it);
            }
        });
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        updateMoreBtn();
        loadMenu();
    }

    private void updateImgToggleLabel() {
        imgToggleBtn.setText(Prefs.imagesEnabled(this) ? "图片：开" : "图片：关");
    }

    private void onRefresh() {
        if (!tabsReady || currentTab < 0) {
            fetchMenu(false);
            return;
        }
        TabItem t = tabs.get(currentTab);
        loadSectionFirst(t.section);
    }

    private void setStatus(String s) {
        if (s == null) {
            statusView.setVisibility(View.GONE);
        } else {
            statusView.setText(s);
            statusView.setVisibility(View.VISIBLE);
        }
    }

    // ---------------- menu -> tabs ----------------

    private void loadMenu() {
        String cached = Cache.read(this, Cache.MENU_KEY);
        if (cached != null) {
            List<Models.Section> sections = parseSections(cached);
            if (sections != null && !sections.isEmpty()) {
                buildTabs(sections);
                long age = System.currentTimeMillis() - Cache.mtime(this, Cache.MENU_KEY);
                if (age < MENU_TTL_MS) {
                    return; // fresh cache, nothing to do
                }
                fetchMenu(true); // stale cache on screen; refresh silently
                return;
            }
        }
        setStatus("正在加载栏目…");
        fetchMenu(false);
    }

    private void fetchMenu(final boolean silent) {
        if (fetchingMenu) return;
        fetchingMenu = true;
        Bg.run(new Bg.Work<String>() {
            @Override
            public String run() throws Exception {
                return Api.fetchRaw(Api.menuUrl());
            }
        }, new Bg.Done<String>() {
            @Override
            public void onDone(String raw, Exception error) {
                fetchingMenu = false;
                if (error == null && raw != null) {
                    List<Models.Section> sections = parseSections(raw);
                    if (sections != null && !sections.isEmpty()) {
                        Cache.write(MainActivity.this, Cache.MENU_KEY, raw);
                        buildTabs(sections);
                        return;
                    }
                }
                if (!tabsReady) {
                    setStatus("栏目加载失败，请检查网络后点右上角「刷新」重试。");
                } else if (!silent) {
                    Toast.makeText(MainActivity.this, "刷新失败，显示缓存栏目",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /**
     * Flatten the menu tree into tab sections: the level-2 entries of the
     * 「首页」 and 「话题」 groups, in menu order. Falls back to every group with
     * subsections when neither group is present.
     */
    private List<Models.Section> parseSections(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray data = root.optJSONArray("data");
            if (data == null) return null;
            List<String> groupNames = new ArrayList<String>();
            List<List<Models.Section>> groups = new ArrayList<List<Models.Section>>();
            boolean hasPreferred = false;
            for (int i = 0; i < data.length(); i++) {
                JSONObject l1 = data.optJSONObject(i);
                if (l1 == null) continue;
                JSONArray subs = l1.optJSONArray("subsection");
                if (subs == null || subs.length() == 0) continue;
                List<Models.Section> sections = new ArrayList<Models.Section>();
                for (int j = 0; j < subs.length(); j++) {
                    JSONObject so = subs.optJSONObject(j);
                    if (so == null) continue;
                    Models.Section s = Models.toSection(so);
                    if (s.feed.length() == 0) continue;
                    sections.add(s);
                }
                if (sections.isEmpty()) continue;
                String gname = l1.optString("name", "");
                groupNames.add(gname);
                groups.add(sections);
                if ("首页".equals(gname) || "话题".equals(gname)) hasPreferred = true;
            }
            List<Models.Section> flat = new ArrayList<Models.Section>();
            for (int i = 0; i < groups.size(); i++) {
                if (hasPreferred
                        && !"首页".equals(groupNames.get(i))
                        && !"话题".equals(groupNames.get(i))) {
                    continue;
                }
                flat.addAll(groups.get(i));
            }
            return flat.isEmpty() ? null : flat;
        } catch (Exception e) {
            return null;
        }
    }

    /** (Re)build the tab strip from sections, in plain menu order. */
    private void buildTabs(List<Models.Section> sections) {
        boolean initial = !tabsReady;
        String wantKey = initial ? Prefs.lastTab(this) : currentKey();

        tabs.clear();
        for (Models.Section s : sections) {
            tabs.add(new TabItem(s));
        }

        int idx = -1;
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).key().equals(wantKey)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) idx = 0;

        tabsReady = true;
        renderTabViews();
        boolean selectionChanged = idx != currentTab || !tabs.get(idx).key().equals(wantKey);
        selectTab(idx, initial || selectionChanged);
    }

    private String currentKey() {
        if (currentTab >= 0 && currentTab < tabs.size()) {
            return tabs.get(currentTab).key();
        }
        return "";
    }

    private void renderTabViews() {
        tabBar.removeAllViews();
        tabViews.clear();
        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            TextView t = Ui.text(this, tabs.get(i).label(), 17, true);
            t.setSingleLine(true);
            t.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = Ui.dp(this, 8);
            t.setLayoutParams(lp);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (index != currentTab) {
                        selectTab(index, true);
                    }
                }
            });
            tabBar.addView(t);
            tabViews.add(t);
        }
        styleTabs();
    }

    private void styleTabs() {
        for (int i = 0; i < tabViews.size(); i++) {
            TextView t = tabViews.get(i);
            boolean selected = i == currentTab;
            // Resting look unchanged (selected: black on white text; other:
            // white on black text). The pressed state inverts whichever look
            // is resting, so a tap on a tab also flashes inverted on e-ink,
            // like every Ui.button in the app.
            GradientDrawable rest = new GradientDrawable();
            rest.setColor(selected ? Color.BLACK : Color.WHITE);
            rest.setStroke(2, Color.BLACK);
            GradientDrawable pressed = new GradientDrawable();
            pressed.setColor(selected ? Color.WHITE : Color.BLACK);
            pressed.setStroke(2, Color.BLACK);
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_pressed}, pressed);
            bg.addState(new int[]{}, rest);
            t.setBackground(bg);
            t.setTextColor(new ColorStateList(
                    new int[][]{{android.R.attr.state_pressed}, {}},
                    new int[]{selected ? Color.BLACK : Color.WHITE,
                            selected ? Color.WHITE : Color.BLACK}));
        }
    }

    private void selectTab(int index, boolean loadContent) {
        currentTab = index;
        Prefs.setLastTab(this, tabs.get(index).key());
        styleTabs();
        final TextView sel = tabViews.get(index);
        tabScroll.post(new Runnable() {
            @Override
            public void run() {
                tabScroll.scrollTo(Math.max(0, sel.getLeft() - Ui.dp(MainActivity.this, 12)), 0);
            }
        });
        if (loadContent) {
            loadCurrentTab();
        }
    }

    // ---------------- list loading: normal sections ----------------

    private void loadCurrentTab() {
        loadGen++; // invalidate any in-flight load of the previous tab
        loading = false;
        rows.clear();
        seenIds.clear();
        page = 0;
        hasMore = false;
        adapter.notifyDataSetChanged();
        listView.setSelection(0);
        setStatus(null);
        updateMoreBtn();
        TabItem t = tabs.get(currentTab);
        loadSectionFirst(t.section);
    }

    private static boolean isHomepage(Models.Section section) {
        return section != null && HOMEPAGE_FEED.equals(section.feed);
    }

    private void loadSectionFirst(final Models.Section section) {
        if (loading || section == null) return;
        if (isHomepage(section)) {
            loadHomepageFirst(section);
        } else {
            loadPlainFirst(section);
        }
    }

    /** Plain sections: fetch page 1 and show it whole, paid and free mixed. */
    private void loadPlainFirst(final Models.Section section) {
        loading = true;
        final int gen = ++loadGen;
        setStatus("正在加载…");
        updateMoreBtn();
        Bg.run(new Bg.Work<String>() {
            @Override
            public String run() throws Exception {
                return Api.fetchRaw(Api.listUrl(section, 1));
            }
        }, new Bg.Done<String>() {
            @Override
            public void onDone(String raw, Exception error) {
                if (gen != loadGen) return;
                loading = false;
                if (error == null && raw != null && applyPage(raw, true, false)) {
                    Cache.write(MainActivity.this, Cache.listKey(section.feed), raw);
                    setStatus(null);
                    updateMoreBtn();
                    return;
                }
                // Network failed: fall back to the cached first page if we have one.
                String cached = Cache.read(MainActivity.this, Cache.listKey(section.feed));
                if (cached != null && applyPage(cached, true, false)) {
                    setStatus("网络不可用，显示上次缓存的内容（点右上角「刷新」重试）");
                } else {
                    setStatus("加载失败，请检查网络后点右上角「刷新」重试。");
                }
                updateMoreBtn();
            }
        });
    }

    /** Homepage only: v1.4 forward-scan batch, paid cards filtered out. */
    private void loadHomepageFirst(final Models.Section section) {
        loading = true;
        final int gen = ++loadGen;
        setStatus("正在加载…");
        updateMoreBtn();
        Bg.run(new Bg.Work<ScanResult>() {
            @Override
            public ScanResult run() {
                return scanSection(section, 1, FIRST_BATCH_TARGET,
                        new HashSet<String>(), gen, true);
            }
        }, new Bg.Done<ScanResult>() {
            @Override
            public void onDone(ScanResult r, Exception error) {
                if (gen != loadGen) return;
                loading = false;
                if (error == null && r != null && !r.firstPageFailed) {
                    if (r.firstRaw != null) {
                        Cache.write(MainActivity.this, Cache.listKey(section.feed), r.firstRaw);
                    }
                    rows.clear();
                    rows.addAll(r.visible);
                    seenIds.clear();
                    seenIds.addAll(r.seen);
                    page = r.lastPageNo;
                    // A mid-batch failure keeps hasMore: the failed page was not
                    // consumed, so the next batch retries it.
                    hasMore = r.failed || r.srcHasMore;
                    adapter.notifyDataSetChanged();
                    if (r.visible.isEmpty()) {
                        setStatus("首页暂时没有可显示的免费文章（已翻 " + r.pagesScanned + " 页）");
                    } else {
                        setStatus(null);
                    }
                    updateMoreBtn();
                    return;
                }
                // First source page failed: fall back to the cached first page.
                String cached = Cache.read(MainActivity.this, Cache.listKey(section.feed));
                if (cached != null && applyPage(cached, true, true)) {
                    setStatus("网络不可用，显示上次缓存的内容（点右上角「刷新」重试）");
                } else {
                    setStatus("加载失败，请检查网络后点右上角「刷新」重试。");
                }
                updateMoreBtn();
            }
        });
    }

    private void loadSectionNext() {
        if (loading || !hasMore || currentTab < 0) return;
        TabItem t = tabs.get(currentTab);
        if (t.section == null) return;
        if (isHomepage(t.section)) {
            loadHomepageNext(t.section);
        } else {
            loadPlainNext(t.section);
        }
    }

    /** Plain sections: append exactly one more source page, unfiltered. */
    private void loadPlainNext(final Models.Section section) {
        loading = true;
        final int gen = loadGen;
        updateMoreBtn();
        final int nextPage = page + 1;
        Bg.run(new Bg.Work<String>() {
            @Override
            public String run() throws Exception {
                return Api.fetchRaw(Api.listUrl(section, nextPage));
            }
        }, new Bg.Done<String>() {
            @Override
            public void onDone(String raw, Exception error) {
                if (gen != loadGen) return;
                loading = false;
                if (error == null && raw != null) {
                    if (!applyPage(raw, false, false)) {
                        hasMore = false;
                    }
                } else {
                    Toast.makeText(MainActivity.this, "下一页加载失败，请重试",
                            Toast.LENGTH_SHORT).show();
                }
                updateMoreBtn();
            }
        });
    }

    /** Homepage only: v1.4 forward-scan batch, paid cards filtered out. */
    private void loadHomepageNext(final Models.Section section) {
        loading = true;
        final int gen = loadGen;
        updateMoreBtn();
        final int startPage = page + 1;
        final HashSet<String> seenSeed = new HashSet<String>(seenIds);
        final boolean hadRows = !rows.isEmpty();
        Bg.run(new Bg.Work<ScanResult>() {
            @Override
            public ScanResult run() {
                return scanSection(section, startPage, NEXT_BATCH_TARGET, seenSeed, gen, false);
            }
        }, new Bg.Done<ScanResult>() {
            @Override
            public void onDone(ScanResult r, Exception error) {
                if (gen != loadGen) return;
                loading = false;
                if (error != null || r == null || r.firstPageFailed) {
                    // Nothing consumed; page/hasMore stay so the tap can retry.
                    Toast.makeText(MainActivity.this, "下一页加载失败，请重试",
                            Toast.LENGTH_SHORT).show();
                    updateMoreBtn();
                    return;
                }
                seenIds.clear();
                seenIds.addAll(r.seen);
                rows.addAll(r.visible);
                page = r.lastPageNo;
                hasMore = r.failed || r.srcHasMore;
                adapter.notifyDataSetChanged();
                if (!r.visible.isEmpty()) {
                    setStatus(null);
                } else if (hadRows) {
                    Toast.makeText(MainActivity.this,
                            r.failed ? "下一页加载失败，请重试"
                                    : "这一段也全是付费文章（已翻 " + r.pagesScanned + " 页）",
                            Toast.LENGTH_SHORT).show();
                } else {
                    setStatus("首页暂时没有可显示的免费文章（已翻 " + r.pagesScanned + " 页）");
                }
                updateMoreBtn();
            }
        });
    }

    // ---------------- forward-scan batches (v1.4; homepage only since v1.5) ----------------

    /** Outcome of one forward-scan batch over a section's source pages. */
    private static final class ScanResult {
        final List<Models.Card> visible = new ArrayList<Models.Card>();
        final HashSet<String> seen = new HashSet<String>();
        String firstRaw = null;
        int pagesScanned = 0;
        int lastPageNo = 0;
        boolean srcHasMore = false;
        boolean failed = false;
        boolean firstPageFailed = false;
    }

    /** One parsed source page: flattened cards plus the pagination cursor. */
    private static final class ParsedPage {
        LinkedHashMap<String, Models.Card> flat;
        boolean hasPagination = false;
        int nextVal = -1;
    }

    /** Parse one raw list page; null when the payload has no usable structure. */
    private ParsedPage parseListPage(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return null;
            ParsedPage pp = new ParsedPage();
            pp.flat = new LinkedHashMap<String, Models.Card>();
            Models.flattenInto(data.opt("result"), pp.flat);
            JSONObject pagination = data.optJSONObject("pagination");
            if (pagination != null) {
                pp.hasPagination = true;
                pp.nextVal = pagination.optInt("next", -1);
            }
            return pp;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Background work (runs on the single Bg executor): sequentially fetch
     * source pages of one section starting at startPageNo, parse + dedupe +
     * paid-filter them, and accumulate visible rows until the batch's visible
     * target is met, MAX_SCAN_PAGES pages were scanned, the source pagination
     * ends, or a fetch/parse fails. Scanning stops early when gen goes stale
     * (tab switched); the stale result is dropped by the caller's gen check.
     *
     * The batch is applied to rows/page/hasMore in one go by the Done
     * callback, so no per-page guard can kill pagination mid-batch (the old
     * applyPage "parsedNew == 0 -> hasMore = false" rule only misfires on
     * all-duplicate pages; here such a page just gets scanned past).
     */
    private ScanResult scanSection(final Models.Section section, int startPageNo,
                                   int targetVisible, HashSet<String> seenSeed,
                                   final int gen, boolean captureFirstRaw) {
        ScanResult r = new ScanResult();
        r.seen.addAll(seenSeed);
        r.lastPageNo = startPageNo - 1;
        int pageNo = startPageNo;
        while (r.pagesScanned < MAX_SCAN_PAGES && r.visible.size() < targetVisible) {
            if (gen != loadGen) break; // tab switched; result will be dropped
            String raw = null;
            try {
                raw = Api.fetchRaw(Api.listUrl(section, pageNo));
            } catch (Exception e) {
                raw = null;
            }
            ParsedPage pp = raw == null ? null : parseListPage(raw);
            if (pp == null) {
                r.failed = true;
                if (r.pagesScanned == 0) r.firstPageFailed = true;
                break;
            }
            if (captureFirstRaw && r.pagesScanned == 0) r.firstRaw = raw;
            int newOnPage = 0;
            for (String id : pp.flat.keySet()) {
                if (!r.seen.contains(id)) newOnPage++;
            }
            boolean hasNext = pp.hasPagination ? pp.nextVal > pageNo : newOnPage > 0;
            r.pagesScanned++;
            r.lastPageNo = pageNo;
            for (Models.Card c : pp.flat.values()) {
                if (!r.seen.add(c.documentId)) continue;
                if (c.paid) continue; // homepage shows free articles only
                r.visible.add(c);
            }
            r.srcHasMore = hasNext;
            postScanProgress(gen, r.pagesScanned);
            if (!hasNext) break;
            pageNo++;
        }
        return r;
    }

    private void postScanProgress(final int gen, final int scanned) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                if (gen == loadGen && loading) {
                    setStatus("正在加载…（已翻 " + scanned + " 页）");
                }
            }
        });
    }

    /**
     * Parse one list page and merge its cards into rows. Returns false when
     * the payload has no usable structure at all.
     *
     * Used by the plain sections' page-by-page loads (filterPaid = false:
     * paid and free cards show mixed, in feed order) and by the homepage's
     * cached-first-page fallback when the network is down (filterPaid =
     * true). The homepage's network path goes through scanSection batches
     * instead. The structural page guards below count cards before the
     * filter, so an all-paid page does not prematurely end pagination.
     */
    private boolean applyPage(String raw, boolean replace, boolean filterPaid) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return false;
            LinkedHashMap<String, Models.Card> flat = new LinkedHashMap<String, Models.Card>();
            Models.flattenInto(data.opt("result"), flat);
            if (replace) {
                rows.clear();
                seenIds.clear();
                page = 1;
            } else {
                page = page + 1;
            }
            int parsedNew = 0;
            for (Models.Card c : flat.values()) {
                if (!seenIds.add(c.documentId)) continue;
                parsedNew++;
                if (filterPaid && c.paid) continue; // homepage: free only
                rows.add(c);
            }
            JSONObject pagination = data.optJSONObject("pagination");
            if (pagination != null) {
                int next = pagination.optInt("next", -1);
                hasMore = next > page;
            } else {
                hasMore = parsedNew > 0;
            }
            if (!replace && parsedNew == 0) hasMore = false;
            adapter.notifyDataSetChanged();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void updateMoreBtn() {
        if (loading) {
            moreBtn.setText("加载中…");
            moreBtn.setEnabled(false);
        } else if (!hasMore) {
            moreBtn.setText("已无更多");
            moreBtn.setEnabled(false);
        } else {
            moreBtn.setText("加载下一页");
            moreBtn.setEnabled(true);
        }
    }

    // ---------------- list adapter ----------------

    private static final class Holder {
        TextView headline;
        TextView meta;
        ImageView img;
        String imgUrl;
    }

    private final class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Holder h;
            LinearLayout row;
            if (convertView == null) {
                h = new Holder();
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(MainActivity.this, 14), Ui.dp(MainActivity.this, 12),
                        Ui.dp(MainActivity.this, 14), Ui.dp(MainActivity.this, 12));

                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                h.headline = Ui.text(MainActivity.this, "", 17, false);
                h.headline.setLineSpacing(0, 1.15f);
                col.addView(h.headline);
                h.meta = Ui.text(MainActivity.this, "", 13, false);
                h.meta.setTextColor(Ui.GREY);
                LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                mLp.topMargin = Ui.dp(MainActivity.this, 6);
                h.meta.setLayoutParams(mLp);
                col.addView(h.meta);
                row.addView(col, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1));

                h.img = new ImageView(MainActivity.this);
                h.img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(
                        Ui.dp(MainActivity.this, 84), Ui.dp(MainActivity.this, 64));
                iLp.leftMargin = Ui.dp(MainActivity.this, 10);
                h.img.setLayoutParams(iLp);
                row.addView(h.img);
                row.setTag(h);
            } else {
                row = (LinearLayout) convertView;
                h = (Holder) row.getTag();
            }

            Models.Card c = rows.get(position);
            h.headline.setText(c.headline);
            StringBuilder meta = new StringBuilder();
            if (c.sectionName.length() > 0) meta.append(c.sectionName);
            String time = Ui.formatTime(c.pubTimeSec);
            if (time.length() > 0) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(time);
            }
            h.meta.setText(meta.toString());

            if (imagesOn && c.thumb.length() > 0) {
                h.img.setVisibility(View.VISIBLE);
                h.imgUrl = c.thumb;
                Bitmap hitBmp = Images.cached(c.thumb, 240);
                if (hitBmp != null) {
                    h.img.setImageBitmap(hitBmp);
                } else {
                    h.img.setImageBitmap(null);
                    final String want = c.thumb;
                    Bg.run(new Bg.Work<Bitmap>() {
                        @Override
                        public Bitmap run() {
                            return Images.load(want, 240);
                        }
                    }, new Bg.Done<Bitmap>() {
                        @Override
                        public void onDone(Bitmap bmp, Exception error) {
                            if (bmp != null && want.equals(h.imgUrl)) {
                                h.img.setImageBitmap(bmp);
                            }
                        }
                    });
                }
            } else {
                h.img.setVisibility(View.GONE);
                h.imgUrl = null;
                h.img.setImageBitmap(null);
            }
            return row;
        }
    }
}
