package com.jingwei.aikeyboard;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    public static final String PREFS = "jingwei_ai_keyboard";
    public static final String KEY_BACKEND = "backend_url";
    public static final String KEY_TOKEN = "app_token";
    public static final String KEY_STYLE = "personal_style";

    private EditText backendEdit;
    private EditText tokenEdit;
    private EditText styleEdit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(26), dp(20), dp(30));
        scroll.addView(root);

        TextView title = text("景威AI键盘", 28, true);
        root.addView(title);
        TextView sub = text("V0.6.1 · 百度式紧凑布局 / 中文拼音 / AI回复", 15, false);
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub);

        root.addView(section("① 启用输入法"));
        root.addView(text("第一次使用：先在系统里启用“景威AI键盘”，再点“选择输入法”把它切出来。现在键盘已支持本地中文拼音输入，并保留“切换键盘”随时返回系统输入法。", 14, false));

        Button enable = button("打开输入法设置");
        enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        root.addView(enable, margins());

        Button choose = button("选择输入法");
        choose.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
        root.addView(choose, margins());

        Button micPermission = button("授权语音输入（麦克风）");
        micPermission.setOnClickListener(v -> {
            if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1001);
            } else {
                Toast.makeText(this, "麦克风权限已开启", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(micPermission, margins());

        root.addView(section("② AI连接（可稍后设置）"));
        root.addView(text("不填也能使用演示模式。连接自己的AI代理后，才会根据具体聊天内容真正生成三条回复。不要把 OpenAI API Key 直接填在这里。", 14, false));

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);

        backendEdit = edit("AI代理地址，例如 https://xxxx.workers.dev/reply");
        backendEdit.setSingleLine(true);
        backendEdit.setText(sp.getString(KEY_BACKEND, ""));
        root.addView(backendEdit, margins());

        tokenEdit = edit("APP Token（你自己设置的一串密码）");
        tokenEdit.setSingleLine(true);
        tokenEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenEdit.setText(sp.getString(KEY_TOKEN, ""));
        root.addView(tokenEdit, margins());

        root.addView(section("③ 我的表达风格"));
        styleEdit = edit("例如：自然、温暖、有分寸，不端着；面对学生像靠谱的哥哥；客户沟通先共情再推进，不制造虚假承诺；朋友圈重故事感，少说教。 ");
        styleEdit.setMinLines(5);
        styleEdit.setGravity(android.view.Gravity.TOP);
        styleEdit.setText(sp.getString(KEY_STYLE,
                "自然、真诚、温暖、有分寸，少AI味；面对学生亲近但不过度说教；客户沟通先理解对方，再给清晰下一步；朋友圈有故事感和个人成长感。"));
        root.addView(styleEdit, margins());

        Button save = button("保存设置");
        save.setOnClickListener(v -> {
            sp.edit()
                    .putString(KEY_BACKEND, backendEdit.getText().toString().trim())
                    .putString(KEY_TOKEN, tokenEdit.getText().toString().trim())
                    .putString(KEY_STYLE, styleEdit.getText().toString().trim())
                    .apply();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        });
        root.addView(save, margins());

        root.addView(section("V0.6.1 怎么用"));
        root.addView(text("1. 中文打字：直接输入连续拼音，例如 nihao / woshi / keyi，候选栏选词；空格默认上屏第一候选。\n2. 点“中/英”可切换英文直输。\n3. AI回复：读取剪贴板或当前输入，再选择高情商 / 朋友圈 / 客户 / 润色。\n4. 点击 AI 候选，它会直接写入当前输入框。", 14, false));

        root.addView(section("隐私"));
        root.addView(text("V0.6.1 的普通中文拼音输入完全在本机完成，不会把打字内容发给服务器。只有你主动点击 AI 功能时才会发送所选文本；密码类输入框会暂停 AI 读取。", 13, false));

        setContentView(scroll);
    }

    private TextView section(String s) {
        TextView tv = text(s, 18, true);
        tv.setPadding(0, dp(22), 0, dp(8));
        return tv;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(0xFF222222);
        if (bold) tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return tv;
    }

    private EditText edit(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(15);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackgroundColor(0xFFF2F2F2);
        return e;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(15);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams margins() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(8), 0, dp(4));
        return p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
