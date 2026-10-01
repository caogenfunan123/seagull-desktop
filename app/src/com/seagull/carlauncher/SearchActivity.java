package com.seagull.carlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 应用搜索：输入即时过滤（应用名 + 包名 + 拼音首字母）。
 * 结果可直接启动，或固定到 Dock / 加入文件夹（长按）。
 * 对齐 TODO P0-4：输入 2 个字符出结果，点结果能启动。
 */
public class SearchActivity extends BaseActivity {

    private LauncherModel model;
    private LinearLayout list;
    private EditText input;
    private String query = "";

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        model = new LauncherModel(this);
        setContentView(build());
        input.requestFocus();
        try {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        } catch (Throwable ignore) {}
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Skin.c(R.color.ground));
        root.setPadding(dp(16), dp(12), dp(16), 0);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("← 返回");
        back.setTextColor(Skin.c(R.color.leaf));
        back.setTextSize(15);
        back.setPadding(0, dp(6), dp(12), dp(6));
        back.setOnClickListener(v -> finish());
        head.addView(back);
        root.addView(head);

        input = new EditText(this);
        input.setHint("搜应用名 / 包名 / 拼音首字母");
        input.setHintTextColor(Skin.c(R.color.text_dim));
        input.setTextColor(Skin.c(R.color.text));
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackgroundColor(Skin.c(R.color.card));
        root.addView(input, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { query = s.toString().trim(); fill(); }
        });
        return root;
    }

    @Override protected void onResume() {
        super.onResume();
        model.loadApps();
        fill();
    }

    private void fill() {
        list.removeAllViews();
        if (query.isEmpty()) {
            note("输入至少 1 个字符开始搜索");
            return;
        }
        String q = query.toLowerCase(Locale.ROOT);
        List<LauncherModel.App> hits = new ArrayList<>();
        for (LauncherModel.App a : model.allApps) {
            if (Pinyin.match(a.label, a.pkg, q)) hits.add(a);
        }
        if (hits.isEmpty()) {
            note("没有匹配的应用");
            return;
        }
        for (final LauncherModel.App a : hits) list.addView(row(a));
    }

    private View row(final LauncherModel.App a) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setBackgroundColor(Skin.c(R.color.card));

        ImageView iv = new ImageView(this);
        Drawable d = model.icon(a);
        if (d != null) iv.setImageDrawable(d);
        box.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(a.label);
        name.setTextColor(Skin.c(R.color.text));
        name.setTextSize(15);
        txt.addView(name);
        TextView sub = new TextView(this);
        sub.setText(a.pkg + "   " + Pinyin.initials(a.label));
        sub.setTextColor(Skin.c(R.color.text_dim));
        sub.setTextSize(10);
        txt.addView(sub);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.leftMargin = dp(10);
        box.addView(txt, tp);

        box.setOnClickListener(v -> {
            model.launch(a, true);
            finish();
        });
        box.setOnLongClickListener(v -> {
            menu(a);
            return true;
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(8);
        box.setLayoutParams(p);
        return box;
    }

    private void menu(final LauncherModel.App a) {
        String[] opts = {"固定到 Dock", "加入文件夹", "从 Dock 拿下来", "应用信息"};
        new AlertDialog.Builder(this)
                .setTitle(a.label)
                .setItems(opts, (d, which) -> {
                    switch (which) {
                        case 0:
                            if (model.inDock(a.key())) {
                                toast("已经在 Dock 里了");
                            } else if (model.dock.size() >= LauncherModel.DOCK_MAX) {
                                toast("Dock 最多 " + LauncherModel.DOCK_MAX + " 个");
                            } else {
                                model.toggleDock(a.key());
                                toast("已固定到 Dock");
                            }
                            break;
                        case 1:
                            pickFolder(a);
                            break;
                        case 2:
                            if (model.inDock(a.key())) {
                                model.toggleDock(a.key());
                                toast("已从 Dock 拿下来");
                            } else {
                                toast("本来就不在 Dock 里");
                            }
                            break;
                        default:
                            try {
                                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.parse("package:" + a.pkg)));
                            } catch (Throwable ignore) {}
                    }
                })
                .show();
    }

    /** 加入文件夹：有文件夹就选，没有就先建一个。 */
    private void pickFolder(final LauncherModel.App a) {
        if (model.folders.isEmpty()) {
            newFolderThenAdd(a);
            return;
        }
        final List<LauncherModel.Folder> fs = new ArrayList<>(model.folders);
        String[] names = new String[fs.size() + 1];
        for (int i = 0; i < fs.size(); i++) names[i] = fs.get(i).name + "（" + fs.get(i).keys.size() + "）";
        names[fs.size()] = "新建文件夹…";
        new AlertDialog.Builder(this)
                .setTitle("加入文件夹")
                .setItems(names, (d, which) -> {
                    if (which == fs.size()) {
                        newFolderThenAdd(a);
                    } else {
                        model.addToFolder(fs.get(which), a.key());
                        toast("已加入「" + fs.get(which).name + "」");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void newFolderThenAdd(final LauncherModel.App a) {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("文件夹名字");
        et.setTextColor(Skin.c(R.color.text));
        new AlertDialog.Builder(this)
                .setTitle("新建文件夹")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) name = "文件夹";
                    LauncherModel.Folder f = new LauncherModel.Folder(name);
                    model.folders.add(f);
                    model.addToFolder(f, a.key());
                    toast("已加入「" + name + "」");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void note(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(Skin.c(R.color.text_dim));
        tv.setTextSize(13);
        tv.setPadding(dp(6), dp(24), dp(6), dp(6));
        list.addView(tv);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
