package com.tink.zaobaoeink;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** Full-screen single-image view (grayscale, down-sampled). Tap anywhere to close. */
public class ImageViewerActivity extends Activity {

    public static final String EXTRA_URL = "url";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String url = getIntent().getStringExtra(EXTRA_URL);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        final ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setBackgroundColor(Color.WHITE);
        root.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final TextView status = Ui.text(this, "正在加载图片…", 16, false);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        setContentView(root);

        if (url == null || url.length() == 0) {
            status.setText("图片地址无效，点按任意处返回");
            return;
        }
        final int target = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        Bitmap hit = Images.cached(url, target);
        if (hit != null) {
            img.setImageBitmap(hit);
            status.setVisibility(View.GONE);
            return;
        }
        Bg.run(new Bg.Work<Bitmap>() {
            @Override
            public Bitmap run() {
                return Images.load(url, target);
            }
        }, new Bg.Done<Bitmap>() {
            @Override
            public void onDone(Bitmap bmp, Exception error) {
                if (bmp != null) {
                    img.setImageBitmap(bmp);
                    status.setVisibility(View.GONE);
                } else {
                    status.setText("图片加载失败，点按任意处返回");
                }
            }
        });
    }
}
