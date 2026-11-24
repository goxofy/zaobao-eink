package com.tink.zaobaoeink;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/** Article list for one section: paged, flattened from the layout-card tree. */
public class ArticleListActivity extends Activity {

    private Models.Section section;
    private final List<Models.Card> cards = new ArrayList<Models.Card>();
    private final HashSet<String> seenIds = new HashSet<String>();
    private CardAdapter adapter;
    private TextView statusView;
    private Button moreBtn;
    private int page = 0;
    private boolean hasMore = true;
    private boolean loading = false;
    private boolean imagesOn = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Api.init(this);
        imagesOn = Prefs.imagesEnabled(this);

        section = new Models.Section();
        Intent in = getIntent();
        section.name = in.getStringExtra("name");
        section.feed = in.getStringExtra("feed");
        section.code = in.getStringExtra("code");
        section.targetId = in.getStringExtra("targetId");
        section.targetUrl = in.getStringExtra("targetUrl");
        section.targetName = in.getStringExtra("targetName");
        section.targetCategory = in.getStringExtra("targetCategory");
        if (section.name == null) section.name = "文章列表";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 10);
        bar.setPadding(pad, pad, pad, pad);

        Button backBtn = Ui.button(this, "返回");
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(backBtn);

        TextView title = Ui.text(this, section.name, 19, true);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tLp.leftMargin = Ui.dp(this, 10);
        title.setLayoutParams(tLp);
        bar.addView(title);

        Button refreshBtn = Ui.button(this, "刷新");
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadFirst();
            }
        });
        bar.addView(refreshBtn);
        root.addView(bar);

        View div = Ui.divider(this);
        root.addView(div, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 2)));

        statusView = Ui.text(this, "", 15, false);
        statusView.setTextColor(Ui.GREY);
        statusView.setPadding(pad, pad, pad, pad);
        statusView.setVisibility(View.GONE);
        root.addView(statusView);

        ListView list = new ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(Color.rgb(0x99, 0x99, 0x99)));
        list.setDividerHeight(1);
        adapter = new CardAdapter();
        moreBtn = Ui.button(this, "加载下一页");
        moreBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadNext();
            }
        });
        list.addFooterView(moreBtn);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= cards.size()) return; // footer
                Models.Card c = cards.get(position);
                Intent it = new Intent(ArticleListActivity.this, ArticleActivity.class);
                it.putExtra("documentId", c.documentId);
                it.putExtra("headline", c.headline);
                startActivity(it);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        loadFirst();
    }

    // ---------------- loading ----------------

    private void loadFirst() {
        if (loading) return;
        loading = true;
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
                loading = false;
                if (error == null && raw != null && applyPage(raw, true)) {
                    Cache.write(ArticleListActivity.this, Cache.listKey(section.feed), raw);
                    setStatus(null);
                    updateMoreBtn();
                    return;
                }
                // Network failed: fall back to the cached first page if we have one.
                String cached = Cache.read(ArticleListActivity.this, Cache.listKey(section.feed));
                if (cached != null && applyPage(cached, true)) {
                    setStatus("网络不可用，显示上次缓存的内容（点右上角「刷新」重试）");
                } else {
                    setStatus("加载失败，请检查网络后点右上角「刷新」重试。");
                }
                updateMoreBtn();
            }
        });
    }

    private void loadNext() {
        if (loading || !hasMore) return;
        loading = true;
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
                loading = false;
                if (error == null && raw != null) {
                    if (!applyPage(raw, false)) {
                        hasMore = false;
                    }
                } else {
                    Toast.makeText(ArticleListActivity.this, "下一页加载失败，请重试",
                            Toast.LENGTH_SHORT).show();
                }
                updateMoreBtn();
            }
        });
    }

    /**
     * Parse one list page and merge its cards. Returns false when the payload has
     * no usable structure at all.
     */
    private boolean applyPage(String raw, boolean replace) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return false;
            LinkedHashMap<String, Models.Card> flat = new LinkedHashMap<String, Models.Card>();
            Models.flattenInto(data.opt("result"), flat);
            if (replace) {
                cards.clear();
                seenIds.clear();
                page = 1;
            } else {
                page = page + 1;
            }
            int added = 0;
            for (Models.Card c : flat.values()) {
                if (seenIds.add(c.documentId)) {
                    cards.add(c);
                    added++;
                }
            }
            JSONObject pagination = data.optJSONObject("pagination");
            if (pagination != null) {
                int next = pagination.optInt("next", -1);
                hasMore = next > page;
            } else {
                hasMore = added > 0;
            }
            if (!replace && added == 0) hasMore = false;
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

    private void setStatus(String s) {
        if (s == null) {
            statusView.setVisibility(View.GONE);
        } else {
            statusView.setText(s);
            statusView.setVisibility(View.VISIBLE);
        }
    }

    // ---------------- adapter ----------------

    private static final class Holder {
        TextView headline;
        TextView meta;
        ImageView img;
        String imgUrl;
    }

    private final class CardAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return cards.size();
        }

        @Override
        public Object getItem(int position) {
            return cards.get(position);
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
                row = new LinearLayout(ArticleListActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(ArticleListActivity.this, 14), Ui.dp(ArticleListActivity.this, 12),
                        Ui.dp(ArticleListActivity.this, 14), Ui.dp(ArticleListActivity.this, 12));

                LinearLayout col = new LinearLayout(ArticleListActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                h.headline = Ui.text(ArticleListActivity.this, "", 17, false);
                h.headline.setLineSpacing(0, 1.15f);
                col.addView(h.headline);
                h.meta = Ui.text(ArticleListActivity.this, "", 13, false);
                h.meta.setTextColor(Ui.GREY);
                LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                mLp.topMargin = Ui.dp(ArticleListActivity.this, 6);
                h.meta.setLayoutParams(mLp);
                col.addView(h.meta);
                row.addView(col, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1));

                h.img = new ImageView(ArticleListActivity.this);
                h.img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(
                        Ui.dp(ArticleListActivity.this, 84), Ui.dp(ArticleListActivity.this, 64));
                iLp.leftMargin = Ui.dp(ArticleListActivity.this, 10);
                h.img.setLayoutParams(iLp);
                row.addView(h.img);
                row.setTag(h);
            } else {
                row = (LinearLayout) convertView;
                h = (Holder) row.getTag();
            }

            Models.Card c = cards.get(position);
            if (c.paid) {
                SpannableStringBuilder sb = new SpannableStringBuilder();
                sb.append(" 付费 ");
                sb.setSpan(new BackgroundColorSpan(Color.BLACK), 0, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(Color.WHITE), 0, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append("  ").append(c.headline);
                h.headline.setText(sb);
            } else {
                h.headline.setText(c.headline);
            }
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
