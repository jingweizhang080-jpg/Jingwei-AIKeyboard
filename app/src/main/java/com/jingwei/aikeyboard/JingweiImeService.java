package com.jingwei.aikeyboard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.view.Window;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.inputmethodservice.InputMethodService;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import android.widget.Space;

public class JingweiImeService extends InputMethodService {

    private LinearLayout root;
    private TextView sourceText;
    private LinearLayout candidatesBox;
    private ProgressBar progress;
    private String currentSource = "";
    private final Deque<String> conversationContext = new ArrayDeque<>();
    private String lastClipboardText = "";
    private String currentMode = "reply";
    private String pinyinBuffer = "";
    private boolean chineseMode = true;
    private boolean nineKeyMode = false;
    private PinyinEngine pinyinEngine;
    private LinearLayout pinyinCandidatesBar;
    private LinearLayout composingRow;
    private TextView composingText;
    private Button composingSplitButton;
    private TextView pinyinStatus;
    private List<String> lastPinyinCandidates = new ArrayList<>();
    private LinearLayout keyboardPanel;
    private Button langButton;
    private SpeechRecognizer speechRecognizer;
    private LinearLayout aiPanel;
    private Button aiPanelToggleButton;
    private boolean aiPanelExpanded = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor pinyinExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private final AtomicInteger pinyinGeneration = new AtomicInteger(0);
    private final Handler main = new Handler(Looper.getMainLooper());

    // 长按删除
    private boolean deleting = false;
    private final Runnable deleteRunnable = new Runnable() {
        @Override
        public void run() {
            if (!deleting) return;
            deleteOneChar();
            main.postDelayed(this, 65);
        }
    };

    @Override
    public View onCreateInputView() {
        configureImeWindowForNavigationBar();
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(6), dp(4), dp(6), 0);
        root.setBackgroundColor(0xFFF1F3F8);

        pinyinEngine = new PinyinEngine(this);
        executor.execute(() -> {
            try {
                pinyinEngine.load();
                main.post(() -> {
                    if (pinyinStatus != null) pinyinStatus.setText("中");
                    if (!pinyinBuffer.isEmpty()) showPinyinCandidates();
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (pinyinStatus != null) pinyinStatus.setText("中");
                    toast("本地拼音词库加载失败，已启用基础词库");
                });
            }
        });

        // V0.7.5：主界面只保留一排核心动作，不再重复显示第二排功能卡。
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(2), dp(1), dp(2), dp(1));
        top.setBackground(rounded(0xFFF1F3F8, 12, 0));

        Button replyBtn = compactToolButton("帮我回复");
        replyBtn.setTextColor(0xFF6547F5);
        replyBtn.setOnClickListener(v -> {
            currentMode = "reply";
            ensureAiPanelOpen();
            generate();
        });
        top.addView(replyBtn, compactWeightParams(1.25f));

        Button expressBtn = compactToolButton("帮我表达");
        expressBtn.setOnClickListener(v -> {
            currentMode = "moments";
            ensureAiPanelOpen();
            generate();
        });
        top.addView(expressBtn, compactWeightParams(1.25f));

        Button polishBtn = compactToolButton("润色");
        polishBtn.setOnClickListener(v -> {
            currentMode = "polish";
            ensureAiPanelOpen();
            generate();
        });
        top.addView(polishBtn, compactWeightParams(1.0f));

        Button moreBtn = compactToolButton("更多");
        moreBtn.setOnClickListener(v -> {
            currentMode = "customer";
            ensureAiPanelOpen();
            if (sourceText != null) {
                sourceText.setText("更多场景：客户沟通 / 朋友圈 / 工作表达，可继续在后续版本扩展。");
            }
        });
        top.addView(moreBtn, compactWeightParams(.95f));

        // 独立输入方式入口：不再使用 Dialog，直接在键盘区域内展开选择。
        Button keyboardSwitch = compactToolButton("⌨");
        keyboardSwitch.setContentDescription("切换输入方式");
        keyboardSwitch.setOnClickListener(v -> showKeyboardChooser());
        top.addView(keyboardSwitch, new LinearLayout.LayoutParams(dp(36), dp(38)));

        // 独立 AI 面板开关：只控制中间 AI 区域。
        aiPanelToggleButton = compactToolButton("⌄");
        aiPanelToggleButton.setContentDescription("展开AI面板");
        aiPanelToggleButton.setOnClickListener(v -> toggleAiPanel());
        top.addView(aiPanelToggleButton, new LinearLayout.LayoutParams(dp(34), dp(38)));

        // 整个输入法收起：与 AI 面板开关彻底分开。
        Button hideIme = compactToolButton("﹀");
        hideIme.setContentDescription("收起整个键盘");
        hideIme.setOnClickListener(v -> hideKeyboardNow());
        top.addView(hideIme, new LinearLayout.LayoutParams(dp(34), dp(38)));

        root.addView(top, fullMargins(0,0,0,3));

        // AI 中间区只显示“当前内容 + 生成结果”，去掉重复的四张功能卡。
        aiPanel = new LinearLayout(this);
        aiPanel.setOrientation(LinearLayout.VERTICAL);
        aiPanel.setVisibility(View.GONE);
        aiPanel.setPadding(dp(8), dp(5), dp(8), dp(6));
        aiPanel.setBackground(rounded(0xFFFFFFFF, 14, 0xFFE3E6ED));

        sourceText = label("复制一条对方消息，然后点「帮我回复」", 12, false);
        sourceText.setTextColor(0xFF74777F);
        sourceText.setPadding(dp(2), dp(1), dp(2), dp(4));
        sourceText.setMaxLines(2);
        aiPanel.addView(sourceText, fullMargins(0,0,0,2));

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        aiPanel.addView(progress, new LinearLayout.LayoutParams(dp(20), dp(20)));

        ScrollView candidatesScroll = new ScrollView(this);
        candidatesBox = new LinearLayout(this);
        candidatesBox.setOrientation(LinearLayout.VERTICAL);
        candidatesScroll.addView(candidatesBox);
        aiPanel.addView(candidatesScroll,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        root.addView(aiPanel, fullMargins(0,0,0,3));

        // 拼音组合行：让正在输入的拼音和候选区连成一个整体。
        composingRow = new LinearLayout(this);
        composingRow.setOrientation(LinearLayout.HORIZONTAL);
        composingRow.setGravity(Gravity.CENTER_VERTICAL);
        composingRow.setVisibility(View.GONE);
        composingRow.setPadding(dp(10), 0, dp(4), 0);
        composingRow.setBackground(rounded(0xFFFFFFFF, 11, 0xFFE2E5EB));

        composingText = label("", 13, false);
        composingText.setTextColor(0xFF6A6E76);
        composingText.setSingleLine(true);
        composingRow.addView(composingText, new LinearLayout.LayoutParams(0, dp(28), 1f));

        composingSplitButton = compactToolButton("分词");
        composingSplitButton.setTextSize(11);
        composingSplitButton.setOnClickListener(v -> handlePinyinSeparator());
        composingRow.addView(composingSplitButton, new LinearLayout.LayoutParams(dp(48), dp(28)));
        root.addView(composingRow, fullMargins(0,0,0,1));

        // 候选词条：白色整条，减少割裂感。
        LinearLayout candidateStrip = new LinearLayout(this);
        candidateStrip.setOrientation(LinearLayout.HORIZONTAL);
        candidateStrip.setGravity(Gravity.CENTER_VERTICAL);
        candidateStrip.setBackground(rounded(0xFFFFFFFF, 11, 0xFFE2E5EB));
        candidateStrip.setPadding(dp(4), 0, dp(4), 0);

        pinyinStatus = label("中", 13, true);
        pinyinStatus.setTextColor(0xFF5F6368);
        pinyinStatus.setGravity(Gravity.CENTER_VERTICAL);
        pinyinStatus.setPadding(dp(10), 0, dp(8), 0);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        candidateStrip.addView(pinyinStatus, statusLp);

        HorizontalScrollView pinyinScroll = new HorizontalScrollView(this);
        pinyinScroll.setHorizontalScrollBarEnabled(false);
        pinyinCandidatesBar = new LinearLayout(this);
        pinyinCandidatesBar.setOrientation(LinearLayout.HORIZONTAL);
        pinyinCandidatesBar.setGravity(Gravity.CENTER_VERTICAL);
        pinyinScroll.addView(pinyinCandidatesBar);
        candidateStrip.addView(pinyinScroll, new LinearLayout.LayoutParams(0, dp(32), 1f));
        TextView arrow = label("⌄", 19, false);
        arrow.setTextColor(0xFF6B6F78);
        arrow.setGravity(Gravity.CENTER);
        arrow.setOnClickListener(v -> showKeyboardChooser());
        candidateStrip.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(32)));
        root.addView(candidateStrip, fullMargins(0,0,0,3));

        keyboardPanel = new LinearLayout(this);
        keyboardPanel.setOrientation(LinearLayout.VERTICAL);
        root.addView(keyboardPanel, fullMargins(0,0,0,0));
        showLetterKeyboard();

        // 让 Android 系统导航栏与键盘底色一致，避免底部出现一条明显更深的“断层”。
        try {
            if (getWindow() != null && getWindow().getWindow() != null) {
                android.view.Window w = getWindow().getWindow();
                w.setNavigationBarColor(0xFFF1F3F8);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    w.setNavigationBarDividerColor(0xFFF1F3F8);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    w.setNavigationBarContrastEnforced(false);
                }
                w.getDecorView().setSystemUiVisibility(
                        w.getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
        } catch (Throwable ignored) {}

        readClipboard();
        applyBottomSafeInset(root);
        return root;
    }

    private void toggleAiPanel() {
        aiPanelExpanded = !aiPanelExpanded;
        if (aiPanel != null) {
            aiPanel.setVisibility(aiPanelExpanded ? View.VISIBLE : View.GONE);
        }
        if (aiPanelToggleButton != null) {
            aiPanelToggleButton.setText(aiPanelExpanded ? "⌃" : "⌄");
            aiPanelToggleButton.setContentDescription(
                    aiPanelExpanded ? "收起AI面板" : "展开AI面板");
        }
    }

    private void ensureAiPanelOpen() {
        if (!aiPanelExpanded) {
            aiPanelExpanded = true;
            if (aiPanel != null) aiPanel.setVisibility(View.VISIBLE);
            if (aiPanelToggleButton != null) {
                aiPanelToggleButton.setText("⌃");
                aiPanelToggleButton.setContentDescription("收起AI面板");
            }
        }
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        deleting = false;
        main.removeCallbacks(deleteRunnable);
        resetPinyinComposition();

        if (root != null && isSensitiveField(attribute)) {
            currentSource = "";
            sourceText.setText("🔒 检测到密码/敏感输入框，AI读取已暂停。请切回普通输入框。 ");
            candidatesBox.removeAllViews();
        }
    }

    private Button modeButton(String text, String mode) {
        Button b = tinyButton(text);
        b.setOnClickListener(v -> {
            currentMode = mode;
            generate();
        });
        return b;
    }


    private String getClipboardText() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return "";
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return "";
        CharSequence cs = clip.getItemAt(0).coerceToText(this);
        return cs == null ? "" : limit(cs.toString().trim(), 4000);
    }

    private void rememberContext(String role, String text) {
        if (text == null) return;
        String cleaned = text.replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) return;

        String entry = role + "：" + limit(cleaned, 1200);
        if (!conversationContext.isEmpty() && entry.equals(conversationContext.peekLast())) return;

        conversationContext.addLast(entry);
        while (conversationContext.size() > 8) conversationContext.removeFirst();
    }

    private String buildContextualSource(String latest) {
        StringBuilder sb = new StringBuilder();
        if (!conversationContext.isEmpty()) {
            sb.append("【最近对话上下文】\\n");
            for (String item : conversationContext) {
                // Avoid duplicating the latest copied message twice.
                if (item.endsWith(latest)) continue;
                sb.append(item).append("\\n");
            }
        }
        sb.append("【当前需要处理的消息】\\n").append(latest);
        sb.append("\\n\\n请优先承接上下文，不要把当前消息当成孤立的一句话；");
        sb.append("如果上下文不足，不要编造不存在的事实或关系。");
        return sb.toString();
    }

    private boolean refreshLatestChatFromClipboard(boolean showUi) {
        String clip = getClipboardText();
        if (clip.isEmpty()) return false;

        currentSource = clip;
        if (!clip.equals(lastClipboardText)) {
            rememberContext("对方", clip);
            lastClipboardText = clip;
        }

        if (showUi && sourceText != null) {
            sourceText.setText("当前消息：" + currentSource);
            candidatesBox.removeAllViews();
        }
        return true;
    }

    private void readClipboard() {
        if (isSensitiveField(getCurrentInputEditorInfo())) {
            toast("当前是敏感输入框，已暂停读取");
            return;
        }

        if (refreshLatestChatFromClipboard(true)) return;
        sourceText.setText("剪贴板没有可用文字。先复制一条对方消息。");
    }

    private void readCurrentEditor() {
        if (isSensitiveField(getCurrentInputEditorInfo())) {
            toast("当前是敏感输入框，已暂停读取");
            return;
        }

        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            toast("没有检测到当前输入框");
            return;
        }

        CharSequence before = ic.getTextBeforeCursor(1800, 0);
        CharSequence after = ic.getTextAfterCursor(800, 0);
        String s = (before == null ? "" : before.toString())
                + (after == null ? "" : after.toString());
        s = s.trim();

        if (s.isEmpty()) {
            toast("当前输入框里还没有文字");
            return;
        }

        currentSource = limit(s, 4000);
        sourceText.setText("当前输入：" + currentSource);
        candidatesBox.removeAllViews();
    }

    private void showLetterKeyboard() {
        if (keyboardPanel == null) return;
        nineKeyMode = false;
        resetPinyinComposition();
        try {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.finishComposingText();
        } catch (Throwable ignored) {}
        keyboardPanel.removeAllViews();
        String[][] rows = {{"Q","W","E","R","T","Y","U","I","O","P"},{"A","S","D","F","G","H","J","K","L"}};
        addLetterRow(rows[0], 0);
        addLetterRow(rows[1], 18);

        LinearLayout third = keyRow();
        Button shift = keyButton("⇧"); shift.setOnClickListener(v -> toast("大写状态即将加入")); third.addView(shift, weightParams(1.25f));
        for (String k : new String[]{"Z","X","C","V","B","N","M"}) { Button b=keyButton(k); b.setOnClickListener(v->handleLetterKey(k.toLowerCase())); third.addView(b, weightParams(1f)); }
        Button del=keyButton("⌫"); del.setOnClickListener(v->deleteOneChar()); del.setOnTouchListener((v,e)->handleDeleteTouch(e)); third.addView(del, weightParams(1.25f));
        keyboardPanel.addView(third, fullMargins(0,1,0,1));

        LinearLayout bottom=keyRow();
        Button symbols=keyButton("符"); symbols.setOnClickListener(v->showMoreSymbols()); bottom.addView(symbols, weightParams(.9f));
        Button nums=keyButton("123"); nums.setOnClickListener(v->showSymbolKeyboard()); bottom.addView(nums, weightParams(1.05f));
        Button comma=keyButton("，"); comma.setOnClickListener(v->commitPunctuation("，", ",")); bottom.addView(comma, weightParams(.8f));
        Button space=keyButton("空格"); space.setOnClickListener(v->handleSpace()); bottom.addView(space, weightParams(2.9f));
        Button period=keyButton("。"); period.setOnClickListener(v->commitPunctuation("。", ".")); bottom.addView(period, weightParams(.8f));
        langButton=keyButton(chineseMode?"中/英":"英/中"); langButton.setOnClickListener(v->{toggleLanguage(); showLetterKeyboard();}); bottom.addView(langButton, weightParams(1.05f));
        Button enter=keyButton("↵"); enter.setOnClickListener(v->insertNewline()); bottom.addView(enter, weightParams(1.05f));
        keyboardPanel.addView(bottom, fullMargins(0,1,0,0));
    }

    private void addLetterRow(String[] keys, int sideInsetDp) {
        LinearLayout row=keyRow();
        row.setPadding(dp(sideInsetDp), dp(1), dp(sideInsetDp), dp(1));
        for(String k:keys){ Button b=keyButton(k); b.setOnClickListener(v->handleLetterKey(k.toLowerCase())); row.addView(b,weightParams(1f)); }
        keyboardPanel.addView(row, fullMargins(0,1,0,1));
    }


    private void showNineKeyKeyboard() {
        if (keyboardPanel == null) return;
        nineKeyMode = true;
        chineseMode = true;
        resetPinyinComposition();
        keyboardPanel.removeAllViews();

        addNineKeyRow(new String[]{"1\\n分词", "2\\nABC", "3\\nDEF", "⌫"});
        addNineKeyRow(new String[]{"4\\nGHI", "5\\nJKL", "6\\nMNO", "重输"});
        addNineKeyRow(new String[]{"7\\nPQRS", "8\\nTUV", "9\\nWXYZ", "0"});

        LinearLayout bottom = keyRow();
        Button symbols = keyButton("符");
        symbols.setOnClickListener(v -> showMoreSymbols());
        bottom.addView(symbols, weightParams(.9f));

        Button nums = keyButton("123");
        nums.setOnClickListener(v -> showSymbolKeyboard());
        bottom.addView(nums, weightParams(1.0f));

        Button space = keyButton("空格");
        space.setOnClickListener(v -> handleSpace());
        bottom.addView(space, weightParams(2.7f));

        langButton = keyButton("中/英");
        langButton.setOnClickListener(v -> {
            toggleLanguage();
            if (chineseMode) {
                showNineKeyKeyboard();
            } else {
                showLetterKeyboard();
            }
        });
        bottom.addView(langButton, weightParams(1.05f));

        Button enter = keyButton("↵");
        enter.setOnClickListener(v -> insertNewline());
        bottom.addView(enter, weightParams(1.05f));

        keyboardPanel.addView(bottom, fullMargins(0,1,0,0));
    }

    private void addNineKeyRow(String[] keys) {
        LinearLayout row = keyRow();
        for (String key : keys) {
            Button b = keyButton(key.replace("\\n", "\n"));
            b.setTextSize(key.length() > 3 ? 13 : 16);

            if ("⌫".equals(key)) {
                b.setOnClickListener(v -> deleteOneChar());
                b.setOnTouchListener((v,e) -> handleDeleteTouch(e));
            } else if ("重输".equals(key)) {
                b.setOnClickListener(v -> resetPinyinComposition());
            } else if ("0".equals(key)) {
                b.setOnClickListener(v -> handleSpace());
            } else if (key.startsWith("1")) {
                b.setOnClickListener(v -> handlePinyinSeparator());
            } else {
                final String digit = key.substring(0, 1);
                b.setOnClickListener(v -> handleNineKeyDigit(digit));
            }

            row.addView(b, weightParams("⌫".equals(key) || "重输".equals(key) || "0".equals(key) ? 1.0f : 1.35f));
        }
        keyboardPanel.addView(row, fullMargins(0,1,0,1));
    }

    private void handleNineKeyDigit(String digit) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        // In English mode 9-key acts as numeric input.
        if (!chineseMode) {
            ic.commitText(digit, 1);
            return;
        }

        // Chinese 9-key: digits are INTERNAL ONLY.
        // Never commit T9 digits directly into the target editor.
        if (pinyinBuffer.length() >= 64) return;

        pinyinBuffer += digit;
        // V0.10: input event ends immediately. Display/candidates are handled by
        // the latest-only pipeline so rapid tapping never waits for decoding.
        showPinyinCandidates();
    }

    private void showEmojiKeyboard() {
        if (keyboardPanel == null) return;
        if (!pinyinBuffer.isEmpty()) commitBestPinyinCandidateOrRaw();
        keyboardPanel.removeAllViews();
        String[][] rows={{"😀","😂","🥹","😊","😍","😘","😎","😭","😡"},{"👍","👏","🙏","💪","🤝","👌","✌","❤️","💔"},{"🎉","✨","🔥","🌹","🌱","🎂","💯","☕","🎁"}};
        for(String[] keys:rows){ LinearLayout row=keyRow(); for(String k:keys){Button b=keyButton(k); b.setOnClickListener(v->insertText(k)); row.addView(b,weightParams(1f));} keyboardPanel.addView(row,fullMargins(0,2,0,2)); }
        LinearLayout bottom=keyRow();
        Button abc=keyButton("ABC"); abc.setOnClickListener(v->showLetterKeyboard()); bottom.addView(abc,weightParams(1.2f));
        Button symbols=keyButton("123"); symbols.setOnClickListener(v->showSymbolKeyboard()); bottom.addView(symbols,weightParams(1f));
        Button space=keyButton("空格"); space.setOnClickListener(v->insertText(" ")); bottom.addView(space,weightParams(3f));
        Button del=keyButton("⌫"); del.setOnClickListener(v->deleteOneChar()); bottom.addView(del,weightParams(1f));
        Button enter=keyButton("↵"); enter.setOnClickListener(v->insertNewline()); bottom.addView(enter,weightParams(1f));
        keyboardPanel.addView(bottom,fullMargins(0,3,0,2));
    }

    private void hideKeyboardNow() {
        try {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.finishComposingText();
        } catch (Throwable ignored) {}

        // InputMethodService-native way to close the IME.
        try {
            requestHideSelf(0);
            return;
        } catch (Throwable ignored) {}

        // Fallback for vendor ROMs.
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && getWindow() != null && getWindow().getWindow() != null) {
                View decor = getWindow().getWindow().getDecorView();
                imm.hideSoftInputFromWindow(decor.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {}
    }

    private void showKeyboardChooser() {
        if (keyboardPanel == null) return;

        // Inline chooser: AlertDialog from InputMethodService is unreliable on
        // some ColorOS/OxygenOS builds because of window-token restrictions.
        resetPinyinComposition();
        keyboardPanel.removeAllViews();

        LinearLayout titleRow = keyRow();
        TextView title = label("选择输入方式", 14, true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(12), 0, 0, 0);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(38), 1f));

        Button close = keyButton("×");
        close.setTextSize(18);
        close.setOnClickListener(v -> showLetterKeyboard());
        titleRow.addView(close, new LinearLayout.LayoutParams(dp(54), dp(38)));
        keyboardPanel.addView(titleRow, fullMargins(0,1,0,2));

        LinearLayout row1 = keyRow();
        Button qwerty = keyButton("拼音26键");
        qwerty.setOnClickListener(v -> showLetterKeyboard());
        row1.addView(qwerty, weightParams(1f));

        Button nine = keyButton("拼音9键");
        nine.setOnClickListener(v -> showNineKeyKeyboard());
        row1.addView(nine, weightParams(1f));
        keyboardPanel.addView(row1, fullMargins(0,2,0,2));

        LinearLayout row2 = keyRow();
        Button emoji = keyButton("Emoji");
        emoji.setOnClickListener(v -> showEmojiKeyboard());
        row2.addView(emoji, weightParams(1f));

        Button symbols = keyButton("符号 / 数字");
        symbols.setOnClickListener(v -> showSymbolKeyboard());
        row2.addView(symbols, weightParams(1f));
        keyboardPanel.addView(row2, fullMargins(0,2,0,2));

        LinearLayout hintRow = keyRow();
        TextView hint = label("手写 / 笔画 / 五笔将在后续接入成熟输入引擎", 12, false);
        hint.setTextColor(0xFF7A7E86);
        hint.setGravity(Gravity.CENTER);
        hintRow.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
        keyboardPanel.addView(hintRow, fullMargins(0,2,0,0));
    }

    private void showSymbolKeyboard() {
        resetPinyinComposition();
        if (keyboardPanel == null) return;
        keyboardPanel.removeAllViews();

        // 分类栏
        LinearLayout tabs = keyRow();
        String[] tabNames = {"常用", "中文", "英文", "数学", "序号", "箭头", "特殊"};
        for (String tabName : tabNames) {
            Button tab = keyButton(tabName);
            tab.setTextSize(12);
            tab.setOnClickListener(v -> renderSymbolCategory(tabName));
            tabs.addView(tab, weightParams(1f));
        }
        keyboardPanel.addView(tabs, fullMargins(0,1,0,2));

        // 符号内容容器
        symbolCategoryContainer = new LinearLayout(this);
        symbolCategoryContainer.setOrientation(LinearLayout.VERTICAL);
        keyboardPanel.addView(symbolCategoryContainer,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        // 底部快捷操作
        LinearLayout bottom = keyRow();

        Button abc = keyButton("ABC");
        abc.setOnClickListener(v -> showLetterKeyboard());
        bottom.addView(abc, weightParams(.9f));

        Button nine = keyButton("九键");
        nine.setOnClickListener(v -> showNineKeyKeyboard());
        bottom.addView(nine, weightParams(.9f));

        Button space = keyButton("空格");
        space.setOnClickListener(v -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.commitText(" ", 1);
        });
        bottom.addView(space, weightParams(1.6f));

        Button back = keyButton("⌫");
        back.setOnClickListener(v -> deleteOneChar());
        bottom.addView(back, weightParams(.9f));

        Button enter = keyButton("↵");
        enter.setOnClickListener(v -> insertNewline());
        bottom.addView(enter, weightParams(.9f));

        keyboardPanel.addView(bottom, fullMargins(0,2,0,0));

        renderSymbolCategory("常用");
    }

    private LinearLayout symbolCategoryContainer;

    private void renderSymbolCategory(String category) {
        if (symbolCategoryContainer == null) return;
        symbolCategoryContainer.removeAllViews();

        String symbols;
        switch (category) {
            case "中文":
                symbols = "， 。 ？ ！ ； ： 、 “ ” ‘ ’ （ ） 【 】 《 》 〈 〉 …… —— · ～";
                break;
            case "英文":
                symbols = ", . ? ! ; : ' \" ( ) [ ] { } < > / \\\\ _ - + = @ # $ % ^ & * ` ~ |";
                break;
            case "数学":
                symbols = "+ − × ÷ = ≠ ≈ ≡ < > ≤ ≥ ± % ‰ ∞ √ ∑ ∏ ∫ ∆ π ° ℃ ℉ ㎡ ³ ²";
                break;
            case "序号":
                symbols = "① ② ③ ④ ⑤ ⑥ ⑦ ⑧ ⑨ ⑩ ❶ ❷ ❸ ❹ ❺ ❻ ❼ ❽ ❾ ❿ Ⅰ Ⅱ Ⅲ Ⅳ Ⅴ Ⅵ Ⅶ Ⅷ Ⅸ Ⅹ";
                break;
            case "箭头":
                symbols = "← → ↑ ↓ ↖ ↗ ↘ ↙ ↔ ↕ ⇒ ⇐ ⇑ ⇓ ⇔ ➜ ➝ ➞ ➟ ➠ ➤ ➥ ➦";
                break;
            case "特殊":
                symbols = "★ ☆ ♥ ♡ ✓ ✔ ✕ ✖ ☑ ☒ ♪ ♫ ♬ ☀ ☁ ☂ ☃ ☺ ☹ © ® ™ § № ※ ◎ ● ○ ◆ ◇ ■ □ ▲ △ ▼ ▽";
                break;
            default:
                symbols = "， 。 ？ ！ 、 ： ； “ ” （ ） …… —— @ # ￥ % & * + - = / \\ ~ · ♥ ★ ✓ → ①";
                break;
        }

        String[] items = symbols.split(" ");
        final int columns = 8;
        LinearLayout row = null;

        for (int i = 0; i < items.length; i++) {
            if (i % columns == 0) {
                row = keyRow();
                symbolCategoryContainer.addView(row, fullMargins(0,1,0,1));
            }

            String symbol = items[i];
            Button key = keyButton(symbol);
            key.setTextSize(18);
            key.setOnClickListener(v -> commitSymbol(symbol));
            row.addView(key, weightParams(1f));
        }

        // 补齐最后一行，避免按键被拉宽。
        if (row != null && items.length % columns != 0) {
            int missing = columns - (items.length % columns);
            for (int i = 0; i < missing; i++) {
                Space filler = new Space(this);
                row.addView(filler, weightParams(1f));
            }
        }
    }

    private void commitSymbol(String symbol) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        // 如果还在拼音组合状态，先结束拼音组合，再提交符号。
        if (!pinyinBuffer.isEmpty()) {
            commitBestPinyinCandidateOrRaw();
        }
        ic.commitText(symbol, 1);
    }

    private void showMoreSymbols() {
        if (keyboardPanel == null) return;
        keyboardPanel.removeAllViews();
        String[][] rows={{"[","]","{","}","#","%","^","*","+","="},{"_","\\","|","~","<",">","€","£","$","•"},{"《","》","“","”","‘","’","【","】","·","—"}};
        for(String[] keys:rows){ LinearLayout row=keyRow(); for(String k:keys){Button b=keyButton(k); b.setOnClickListener(v->insertText(k)); row.addView(b,weightParams(1f));} keyboardPanel.addView(row,fullMargins(0,2,0,2)); }
        LinearLayout bottom=keyRow(); Button back=keyButton("123"); back.setOnClickListener(v->showSymbolKeyboard()); bottom.addView(back,weightParams(1.3f)); Button abc=keyButton("ABC"); abc.setOnClickListener(v->showLetterKeyboard()); bottom.addView(abc,weightParams(1.3f)); Button space=keyButton("空格"); space.setOnClickListener(v->insertText(" ")); bottom.addView(space,weightParams(3.4f)); Button del=keyButton("⌫"); del.setOnClickListener(v->deleteOneChar()); bottom.addView(del,weightParams(1f)); Button enter=keyButton("↵"); enter.setOnClickListener(v->insertNewline()); bottom.addView(enter,weightParams(1.1f)); keyboardPanel.addView(bottom,fullMargins(0,3,0,2));
    }

    private LinearLayout keyRow(){
        LinearLayout r=new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        r.setPadding(0, 0, 0, 0);
        return r;
    }

    private Button keyButton(String text){
        Button b=new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        b.setTextColor(0xFF202124);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(2), 0, dp(2), 0);
        GradientDrawable g=new GradientDrawable();
        boolean function = text.equals("⇧") || text.equals("⌫") || text.equals("123")
                || text.equals("ABC") || text.equals("中/英") || text.equals("英/中")
                || text.equals("⌨") || text.equals("↵") || text.equals("#+=")
                || text.equals("☺") || text.equals("🎙") || text.equals("符") || text.equals("，") || text.equals("。");
        g.setColor(function ? 0xFFD5DAE2 : 0xFFFFFFFF);
        g.setCornerRadius(dp(9));
        g.setStroke(dp(1), function ? 0xFFC6CBD3 : 0xFFDDE1E6);
        b.setBackground(g);
        b.setElevation(dp(1));
        b.setHapticFeedbackEnabled(true);
        b.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                performKeyHaptic(v);
            }
            return false;
        });
        return b;
    }

    private void performKeyHaptic(View view) {
        try {
            if (view != null) {
                boolean handled = view.performHapticFeedback(
                        HapticFeedbackConstants.KEYBOARD_TAP,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                                | HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
                if (handled) return;
            }
        } catch (Throwable ignored) {}

        try {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            // Do not queue old pulses: the newest finger-down always wins.
            vibrator.cancel();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(5, 80));
            } else {
                vibrator.vibrate(5);
            }
        } catch (Throwable ignored) {}
    }

    private GradientDrawable rounded(int color, int radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }

    private Button compactToolButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setTextColor(0xFF303038);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(2),0,dp(2),0);
        b.setBackground(rounded(0xFFFFFFFF, 12, 0xFFE1E4EA));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        lp.setMargins(dp(2),0,dp(2),0); b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout.LayoutParams compactWeightParams(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(39), weight);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private LinearLayout aiCard(String title, String subtitle, String mode) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(4),dp(5),dp(4),dp(5));
        card.setBackground(rounded("reply".equals(mode) ? 0xFFF6F3FF : 0xFFFFFFFF, 14, "reply".equals(mode) ? 0xFF8B72FF : 0xFFE4E6EB));
        TextView t=label(title,13,true); t.setGravity(Gravity.CENTER); if("reply".equals(mode)) t.setTextColor(0xFF6547F5); card.addView(t);
        TextView sub=label(subtitle,10,false); sub.setTextColor(0xFF858891); sub.setGravity(Gravity.CENTER); card.addView(sub);
        card.setOnClickListener(v -> { currentMode=mode; generate(); });
        return card;
    }

    private LinearLayout.LayoutParams weightCardParams(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(52), weight);
        p.setMargins(dp(3),dp(3),dp(3),dp(3));
        return p;
    }

    private Button toolbarButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFF5F6FA);
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), 0xFFDCE0E8);
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        lp.setMargins(dp(3), 0, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void startVoiceInput() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("需要麦克风权限，正在打开授权页面");
            Intent app = new Intent(this, MainActivity.class);
            app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(app);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("手机当前没有可用的系统语音识别服务，可先安装/启用系统语音服务");
            return;
        }
        try {
            if (speechRecognizer != null) { speechRecognizer.cancel(); speechRecognizer.destroy(); }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(android.os.Bundle p) { toast("🎙 正在听，请说话"); }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float r) {}
                @Override public void onBufferReceived(byte[] b) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onError(int e) {
                    String msg;
                    switch (e) {
                        case SpeechRecognizer.ERROR_AUDIO: msg="麦克风录音失败"; break;
                        case SpeechRecognizer.ERROR_CLIENT: msg="语音服务启动失败"; break;
                        case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: msg="没有麦克风权限"; break;
                        case SpeechRecognizer.ERROR_NETWORK: case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: msg="语音识别网络异常"; break;
                        case SpeechRecognizer.ERROR_NO_MATCH: msg="没有听清，请再说一次"; break;
                        case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: msg="语音服务正忙，请稍后再试"; break;
                        case SpeechRecognizer.ERROR_SERVER: msg="系统语音服务异常"; break;
                        case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: msg="没有检测到说话声音"; break;
                        default: msg="语音识别失败（错误码 "+e+"）";
                    }
                    toast(msg);
                }
                @Override public void onResults(android.os.Bundle r) {
                    ArrayList<String> a=r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if(a!=null&&!a.isEmpty()) insertText(a.get(0)); else toast("没有识别到文字");
                }
                @Override public void onPartialResults(android.os.Bundle p) {}
                @Override public void onEvent(int e,android.os.Bundle p) {}
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, chineseMode ? "zh-CN" : "en-US");
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, chineseMode ? "zh-CN" : "en-US");
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            speechRecognizer.startListening(i);
        } catch (Exception e) {
            toast("语音服务启动失败：" + safeMessage(e));
        }
    }

    private void generate() {
        if (isSensitiveField(getCurrentInputEditorInfo())) {
            toast("敏感输入框不启用AI");
            return;
        }

        if ("reply".equals(currentMode) || "customer".equals(currentMode)) {
            // Every AI reply tap refreshes the newest copied incoming message.
            // This prevents reusing an old currentSource from the previous turn.
            refreshLatestChatFromClipboard(true);
        } else if (currentSource.trim().isEmpty()) {
            readClipboard();
        }

        if (currentSource.trim().isEmpty()) {
            toast("先复制/读取一段文字");
            return;
        }

        SharedPreferences sp = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String backend = sp.getString(MainActivity.KEY_BACKEND, "").trim();
        String token = sp.getString(MainActivity.KEY_TOKEN, "").trim();
        String style = sp.getString(MainActivity.KEY_STYLE, "").trim();

        candidatesBox.removeAllViews();
        progress.setVisibility(View.VISIBLE);

        if (backend.isEmpty()) {
            main.postDelayed(() -> showCandidates(localFallback(currentMode, currentSource)), 250);
            return;
        }

        executor.execute(() -> {
            try {
                String requestText = ("reply".equals(currentMode) || "customer".equals(currentMode))
                        ? buildContextualSource(currentSource)
                        : currentSource;
                List<String> result = callBackend(backend, token, currentMode, requestText, style);
                main.post(() -> showCandidates(result));
            } catch (Exception e) {
                main.post(() -> {
                    progress.setVisibility(View.GONE);
                    toast("AI连接失败，已切换演示模式：" + safeMessage(e));
                    showCandidates(localFallback(currentMode, currentSource));
                });
            }
        });
    }

    private List<String> callBackend(String endpoint, String token,
                                     String mode, String text, String style) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (!token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);

        JSONObject body = new JSONObject();
        body.put("mode", mode);
        body.put("text", text);
        body.put("style", style);
        body.put("count", 3);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream is = code >= 200 && code < 300
                ? conn.getInputStream() : conn.getErrorStream();
        String raw = readAll(is);

        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + " " + limit(raw, 160));
        }

        JSONObject obj = new JSONObject(raw);
        JSONArray arr = obj.optJSONArray("candidates");
        if (arr == null || arr.length() == 0) {
            throw new Exception("服务器没有返回 candidates");
        }

        List<String> list = new ArrayList<>();
        for (int i = 0; i < arr.length() && list.size() < 3; i++) {
            String s = arr.optString(i, "").trim();
            if (!s.isEmpty()) list.add(s);
        }

        if (list.isEmpty()) throw new Exception("候选为空");
        return list;
    }

    private void showCandidates(List<String> list) {
        progress.setVisibility(View.GONE);
        candidatesBox.removeAllViews();

        for (int i = 0; i < list.size(); i++) {
            final String candidate = list.get(i);
            TextView card = label((i + 1) + "  " + candidate, 14, false);
            card.setTextColor(0xFF222222);
            card.setBackgroundColor(0xFFFFFFFF);
            card.setPadding(dp(10), dp(8), dp(10), dp(8));
            card.setOnClickListener(v -> commitCandidate(candidate));
            candidatesBox.addView(card, fullMargins(0, 0, 0, 6));
        }
    }
    private void showPinyinCandidates() {
        if (pinyinCandidatesBar == null) return;

        final String snapshot = pinyinBuffer;
        final boolean snapshotNineKey = nineKeyMode;
        final int generation = pinyinGeneration.incrementAndGet();

        if (!chineseMode || snapshot.isEmpty()) {
            pinyinCandidatesBar.removeAllViews();
            lastPinyinCandidates = new ArrayList<>();
            if (composingRow != null) composingRow.setVisibility(View.GONE);
            if (pinyinStatus != null) {
                pinyinStatus.setVisibility(View.VISIBLE);
                pinyinStatus.setText(chineseMode ? "中" : "EN");
            }
            return;
        }

        // This path is deliberately cheap. Never run Beam search on the UI thread.
        String display;
        if (snapshotNineKey) {
            display = pinyinEngine == null ? "" : pinyinEngine.quickDisplayT9(snapshot);
        } else {
            display = pinyinEngine == null
                    ? snapshot.replace("'", " ")
                    : pinyinEngine.formatForDisplay(snapshot);
        }

        if (composingRow != null) composingRow.setVisibility(View.VISIBLE);
        if (composingText != null) composingText.setText(display);
        if (pinyinStatus != null) pinyinStatus.setVisibility(View.GONE);
        if (composingSplitButton != null) {
            composingSplitButton.setVisibility(snapshotNineKey ? View.VISIBLE : View.GONE);
        }

        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            if (snapshotNineKey) {
                if (!display.isEmpty()) ic.setComposingText(display, 1);
            } else {
                ic.setComposingText(snapshot, 1);
            }
        }

        // Keep the old candidates on screen until the newest result is ready. This
        // avoids flicker and makes typing feel continuous.
        pinyinExecutor.execute(() -> {
            List<String> words = pinyinEngine == null
                    ? new ArrayList<>()
                    : (snapshotNineKey ? pinyinEngine.searchT9(snapshot, 10)
                                       : pinyinEngine.search(snapshot, 10));
            main.post(() -> {
                if (generation != pinyinGeneration.get()) return;
                if (!snapshot.equals(pinyinBuffer) || snapshotNineKey != nineKeyMode) return;
                renderPinyinCandidates(words);
            });
        });
    }

    private void renderPinyinCandidates(List<String> words) {
        if (pinyinCandidatesBar == null) return;
        pinyinCandidatesBar.removeAllViews();
        lastPinyinCandidates = new ArrayList<>(words);
        if (words == null || words.isEmpty()) return;

        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            TextView card = label(word, 18, i == 0);
            card.setTextColor(i == 0 ? 0xFF1677FF : 0xFF202124);
            card.setBackgroundColor(0x00FFFFFF);
            card.setPadding(dp(12), dp(5), dp(12), dp(5));
            card.setOnClickListener(v -> commitPinyinCandidate(word));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(1), 0, dp(1), 0);
            pinyinCandidatesBar.addView(card, lp);
        }
    }

    private void handleLetterKey(String letter) {
        if (isSensitiveField(getCurrentInputEditorInfo())) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        if (!chineseMode) {
            ic.commitText(letter, 1);
            return;
        }

        if (pinyinBuffer.length() >= 64) return;
        pinyinBuffer += letter;
        ic.setComposingText(pinyinBuffer, 1);
        showPinyinCandidates();
    }

    private void handlePinyinSeparator() {
        if (!chineseMode || pinyinBuffer.isEmpty()) return;
        if (pinyinBuffer.endsWith("'")) return;
        pinyinBuffer += "'";
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.setComposingText(pinyinBuffer, 1);
        showPinyinCandidates();
    }

    private void commitPinyinCandidate(String word) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(word, 1);
        ic.finishComposingText();
        pinyinGeneration.incrementAndGet();
        pinyinExecutor.getQueue().clear();
        pinyinGeneration.incrementAndGet();
        pinyinExecutor.getQueue().clear();
        pinyinGeneration.incrementAndGet();
        pinyinExecutor.getQueue().clear();
        pinyinGeneration.incrementAndGet();
        pinyinExecutor.getQueue().clear();
        pinyinBuffer = "";
        lastPinyinCandidates.clear();
        if (pinyinCandidatesBar != null) pinyinCandidatesBar.removeAllViews();
    }

    private void commitBestPinyinCandidateOrRaw() {
        if (pinyinBuffer.isEmpty()) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        if (!lastPinyinCandidates.isEmpty()) {
            commitPinyinCandidate(lastPinyinCandidates.get(0));
        } else {
            String raw = nineKeyMode && pinyinEngine != null
                    ? pinyinEngine.displayT9Safe(pinyinBuffer)
                    : pinyinBuffer;
            ic.commitText(raw, 1);
            ic.finishComposingText();
            pinyinBuffer = "";
            if (pinyinCandidatesBar != null) pinyinCandidatesBar.removeAllViews();
        }
    }

    private void handleSpace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (chineseMode && !pinyinBuffer.isEmpty()) {
            commitBestPinyinCandidateOrRaw();
        } else {
            ic.commitText(" ", 1);
        }
    }

    private void toggleLanguage() {
        if (!pinyinBuffer.isEmpty()) commitBestPinyinCandidateOrRaw();
        chineseMode = !chineseMode;
        if (pinyinStatus != null) {
            pinyinStatus.setText(chineseMode
                    ? (pinyinEngine != null && pinyinEngine.isLoaded() ? "中文" : "中文·词库加载中")
                    : "English");
        }
        if (pinyinCandidatesBar != null) pinyinCandidatesBar.removeAllViews();
    }

    private void commitPunctuation(String zh, String en) {
        if (!pinyinBuffer.isEmpty()) commitBestPinyinCandidateOrRaw();
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(chineseMode ? zh : en, 1);
    }

    private void resetPinyinComposition() {
        pinyinGeneration.incrementAndGet();
        pinyinExecutor.getQueue().clear();
        pinyinBuffer = "";
        lastPinyinCandidates.clear();
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.finishComposingText();
        if (pinyinCandidatesBar != null) pinyinCandidatesBar.removeAllViews();
    }

    private void commitCandidate(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            toast("当前没有可写入的输入框");
            return;
        }
        ic.commitText(text, 1);
    }

    // ==================== V0.2 编辑能力 ====================

    private boolean handleDeleteTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                performKeyHaptic(null);
                deleting = true;
                main.removeCallbacks(deleteRunnable);
                main.postDelayed(deleteRunnable, 350);
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                deleting = false;
                main.removeCallbacks(deleteRunnable);
                return false;
            default:
                return false;
        }
    }

    private void deleteOneChar() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        // While composing Chinese pinyin/T9, Backspace must edit the internal
        // composition buffer, NOT the text editor. This keeps the visible text
        // as pinyin and allows the user to fix one key and continue typing.
        if (chineseMode && !pinyinBuffer.isEmpty()) {
            pinyinBuffer = pinyinBuffer.substring(0, pinyinBuffer.length() - 1);

            if (pinyinBuffer.isEmpty()) {
                ic.finishComposingText();
                showPinyinCandidates();
                return;
            }

            // V0.10: let the latest-only pipeline refresh composing/candidates.
            showPinyinCandidates();
            return;
        }

        // Normal deletion when no pinyin composition is active.
        ic.deleteSurroundingText(1, 0);
    }

    private void clearCurrentEditor() {
        if (isSensitiveField(getCurrentInputEditorInfo())) {
            toast("敏感输入框不执行清空操作");
            return;
        }

        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            toast("当前没有可操作的输入框");
            return;
        }

        resetPinyinComposition();
        CharSequence before = ic.getTextBeforeCursor(10000, 0);
        CharSequence after = ic.getTextAfterCursor(10000, 0);
        int beforeLength = before == null ? 0 : before.length();
        int afterLength = after == null ? 0 : after.length();

        if (beforeLength == 0 && afterLength == 0) {
            toast("当前输入框已经是空的");
            return;
        }

        ic.deleteSurroundingText(beforeLength, afterLength);
    }

    private void insertNewline() {
        if (isSensitiveField(getCurrentInputEditorInfo())) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (!pinyinBuffer.isEmpty()) commitBestPinyinCandidateOrRaw();

        // V0.5: 直接提交换行字符。聊天应用常把 KEYCODE_ENTER 当作“发送/完成”，
        // 从而收起键盘；commitText 能更稳定地表达用户明确点击的“换行”。
        ic.commitText("\n", 1);
    }
    private void insertText(String text) {
        if (isSensitiveField(getCurrentInputEditorInfo())) return;

        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        ic.commitText(text, 1);
    }
    private List<String> localFallback(String mode, String src) {
        List<String> out = new ArrayList<>();
        if ("reply".equals(mode)) {
            out.add("看到你这么说还挺开心的🥹 真正重要的不是我说了多少，而是你愿意去尝试、去表达、去慢慢打开自己。继续保持呀，会越来越好的🌱");
            out.add("哈哈，这句话我收下了😼 不过也想把一半夸奖还给你自己——很多改变都是因为你愿意迈出那一步。我只是刚好陪你走了一小段。");
            out.add("谢谢你这么认真地说这些。能让你在这段时间里更敢尝试、更敢表达，对我来说就是很有意义的事情了。继续加油～");
        } else if ("moments".equals(mode)) {
            out.add("有时候真正让人记住的，不是某一天做成了多大的事，而是在某个很普通的瞬间，突然发现自己正在成为曾经想成为的人。今天，又多了一点这样的感觉。");
            out.add("最近越来越觉得，成长不是突然想明白很多大道理，而是在一次次真实的人和事里，慢慢知道自己珍惜什么、想成为什么样的人。 ");
            out.add("记录一个今天的小瞬间。事情不大，但让我挺开心。可能所谓值得，就是你认真做过的事情，也在某个时刻给别人留下了一点温度。 ");
        } else if ("customer".equals(mode)) {
            out.add("我能理解你的顾虑，这种事情确实没必要因为我说几句话就马上决定。你可以先告诉我你现在最担心的是哪一点，我把实际情况跟你讲清楚，你再判断适不适合自己。 ");
            out.add("没关系，先别急着做决定。比起催你，我更希望先把你的需求和顾虑弄明白。如果最后发现确实不适合，我也会直接跟你说。 ");
            out.add("可以的。我们先不谈报不报，你把现在卡住你的点跟我说一下，我帮你一起拆开看看。至少先把信息弄清楚，再决定会更稳妥。 ");
        } else {
            String cleaned = src.replaceAll("\\s+", " ").trim();
            out.add(cleaned);
            out.add("我想表达的是：" + cleaned);
            out.add(cleaned + "。希望这句话能说得自然一点，也更真诚一点。 ");
        }
        return out;
    }

    private boolean isSensitiveField(EditorInfo info) {
        if (info == null) return false;
        int inputType = info.inputType;
        int cls = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;

        if (cls == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        }

        return cls == InputType.TYPE_CLASS_NUMBER
                && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    private String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private String safeMessage(Exception e) {
        String s = e.getMessage();
        return s == null ? e.getClass().getSimpleName() : limit(s, 120);
    }

    private String limit(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private TextView label(String s, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(0xFF222222);
        if (bold) tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return tv;
    }

    private Button tinyButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(9), dp(6), dp(9), dp(6));
        return b;
    }

    private LinearLayout.LayoutParams weightParams(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, dp(39), weight);
        p.setMargins(dp(3), dp(1), dp(3), dp(1));
        return p;
    }

    private LinearLayout.LayoutParams fullMargins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onDestroy() {
        deleting = false;
        main.removeCallbacks(deleteRunnable);
        if (speechRecognizer != null) speechRecognizer.destroy();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void applyBottomSafeInset(View root) {
        if (root == null) return;

        // Keep the IME visually separate from Android's 3-button / gesture navigation area.
        // Android 11+ uses navigationBars() insets; older versions use the legacy inset.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int navBottom = 0;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    navBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                } else {
                    navBottom = insets.getSystemWindowInsetBottom();
                }
            } catch (Throwable ignored) {}

            // On some ColorOS/OxygenOS builds the IME receives 0 or a very small inset
            // even though the 3-button bar is still visually overlaid. Always reserve
            // a small touch-safe gutter. Cap it so the keyboard doesn't become tall.
            int safeBottom = Math.max(dp(30), Math.min(navBottom, dp(38)));

            v.setPadding(
                    v.getPaddingLeft(),
                    v.getPaddingTop(),
                    v.getPaddingRight(),
                    safeBottom
            );
            return insets;
        });
        root.requestApplyInsets();
    }



    private void configureImeWindowForNavigationBar() {
        try {
            if (getWindow() == null || getWindow().getWindow() == null) return;
            Window w = getWindow().getWindow();

            // Match the keyboard background so Android's navigation area no longer
            // looks like a second, darker panel.
            w.setNavigationBarColor(0xFFF2F4F8);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                int flags = w.getDecorView().getSystemUiVisibility();
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                w.getDecorView().setSystemUiVisibility(flags);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                w.setNavigationBarContrastEnforced(false);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            hideKeyboardNow();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

}
