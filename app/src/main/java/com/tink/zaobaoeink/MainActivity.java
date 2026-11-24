package com.tink.zaobaoeink;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Section (menu) page: builds the section list from the navigation menu API. */
public class MainActivity extends Activity {

    private static final long MENU_TTL_MS = 24L * 3600 * 1000;

    private final List<Object> rows = new ArrayList<Object>(); // String header | Models.Section
    private MenuAdapter adapter;
    private TextView statusView;
    private Button imgToggleBtn;
    private boolean fetching = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Api.init(this);

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
                updateImgToggleLabel();
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
                fetchMenu(false);
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
        list.setDivider(null);
        adapter = new MenuAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Object item = rows.get(position);
                if (item instanceof Models.Section) {
                    Models.Section s = (Models.Section) item;
                    Intent it = new Intent(MainActivity.this, ArticleListActivity.class);
                    it.putExtra("name", s.name);
                    it.putExtra("feed", s.feed);
                    it.putExtra("code", s.code);
                    it.putExtra("targetId", s.targetId);
                    it.putExtra("targetUrl", s.targetUrl);
                    it.putExtra("targetName", s.targetName);
                    it.putExtra("targetCategory", s.targetCategory);
                    startActivity(it);
                }
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        loadMenu();
    }

    private void updateImgToggleLabel() {
        imgToggleBtn.setText(Prefs.imagesEnabled(this) ? "图片：开" : "图片：关");
    }

    private void loadMenu() {
        String cached = Cache.read(this, Cache.MENU_KEY);
        if (cached != null && renderMenu(cached)) {
            long age = System.currentTimeMillis() - Cache.mtime(this, Cache.MENU_KEY);
            if (age < MENU_TTL_MS) {
                return; // fresh cache, nothing to do
            }
            fetchMenu(true); // stale cache on screen; refresh silently
            return;
        }
        setStatus("正在加载栏目…");
        fetchMenu(false);
    }

    private void fetchMenu(final boolean silent) {
        if (fetching) return;
        fetching = true;
        Bg.run(new Bg.Work<String>() {
            @Override
            public String run() throws Exception {
                return Api.fetchRaw(Api.menuUrl());
            }
        }, new Bg.Done<String>() {
            @Override
            public void onDone(String raw, Exception error) {
                fetching = false;
                if (error == null && raw != null) {
                    Cache.write(MainActivity.this, Cache.MENU_KEY, raw);
                    if (renderMenu(raw)) {
                        setStatus(null);
                        return;
                    }
                }
                if (rows.isEmpty()) {
                    setStatus("栏目加载失败，请检查网络后点右上角「刷新」重试。");
                } else if (!silent) {
                    Toast.makeText(MainActivity.this, "刷新失败，显示缓存栏目",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** Parse menu JSON into rows. Returns false when the payload is unusable. */
    private boolean renderMenu(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray data = root.optJSONArray("data");
            if (data == null) return false;
            List<Object> newRows = new ArrayList<Object>();
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
                newRows.add(l1.optString("name", ""));
                newRows.addAll(sections);
            }
            if (newRows.isEmpty()) return false;
            rows.clear();
            rows.addAll(newRows);
            adapter.notifyDataSetChanged();
            return true;
        } catch (Exception e) {
            return false;
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

    private final class MenuAdapter extends BaseAdapter {
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
        public int getItemViewType(int position) {
            return rows.get(position) instanceof String ? 0 : 1;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public boolean isEnabled(int position) {
            return rows.get(position) instanceof Models.Section;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Object item = rows.get(position);
            if (item instanceof String) {
                TextView t;
                if (convertView instanceof TextView) {
                    t = (TextView) convertView;
                } else {
                    t = Ui.text(MainActivity.this, "", 15, true);
                    t.setBackgroundColor(Ui.LIGHT_GREY_BG);
                }
                t.setText((String) item);
                t.setPadding(Ui.dp(MainActivity.this, 14), Ui.dp(MainActivity.this, 8),
                        Ui.dp(MainActivity.this, 14), Ui.dp(MainActivity.this, 8));
                return t;
            }
            Models.Section s = (Models.Section) item;
            TextView t;
            if (convertView instanceof TextView) {
                t = (TextView) convertView;
            } else {
                t = Ui.text(MainActivity.this, "", 18, false);
                t.setBackgroundColor(Color.WHITE);
            }
            t.setText(s.name);
            t.setPadding(Ui.dp(MainActivity.this, 18), Ui.dp(MainActivity.this, 16),
                    Ui.dp(MainActivity.this, 18), Ui.dp(MainActivity.this, 16));
            return t;
        }
    }
}
