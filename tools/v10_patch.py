from pathlib import Path
import re

SERVICE = Path('app/src/main/java/com/jingwei/aikeyboard/JingweiImeService.java')
ENGINE = Path('app/src/main/java/com/jingwei/aikeyboard/PinyinEngine.java')

s = SERVICE.read_text(encoding='utf-8')

# Dedicated latest-only executor for pinyin candidates. AI/network work must never
# block typing, and stale candidate jobs must not pile up behind the user's fingers.
if 'java.util.concurrent.ArrayBlockingQueue' not in s:
    s = s.replace(
        'import java.util.concurrent.Executors;\n',
        'import java.util.concurrent.Executors;\n'
        'import java.util.concurrent.ArrayBlockingQueue;\n'
        'import java.util.concurrent.ThreadPoolExecutor;\n'
        'import java.util.concurrent.TimeUnit;\n'
        'import java.util.concurrent.atomic.AtomicInteger;\n'
    )

old_fields = '''    private final ExecutorService executor = Executors.newSingleThreadExecutor();\n    private final Handler main = new Handler(Looper.getMainLooper());'''
new_fields = '''    private final ExecutorService executor = Executors.newSingleThreadExecutor();\n    private final ThreadPoolExecutor pinyinExecutor = new ThreadPoolExecutor(\n            1, 1, 0L, TimeUnit.MILLISECONDS,\n            new ArrayBlockingQueue<>(1),\n            new ThreadPoolExecutor.DiscardOldestPolicy());\n    private final AtomicInteger pinyinGeneration = new AtomicInteger(0);\n    private final Handler main = new Handler(Looper.getMainLooper());'''
if old_fields in s:
    s = s.replace(old_fields, new_fields)

# 9-key ACTION_UP must never run dictionary decoding on the UI thread.
old_nine = '''        pinyinBuffer += digit;\n\n        String composing = pinyinEngine == null ? "" : pinyinEngine.displayT9Safe(pinyinBuffer);\n\n        // If the decoder has a pinyin spelling, use composing text.\n        // If it doesn't yet, keep the editor untouched; the candidate UI can still update.\n        if (!composing.isEmpty()) {\n            ic.setComposingText(composing, 1);\n        }\n\n        showPinyinCandidates();'''
new_nine = '''        pinyinBuffer += digit;\n        // V0.10: input event ends immediately. Display/candidates are handled by\n        // the latest-only pipeline so rapid tapping never waits for decoding.\n        showPinyinCandidates();'''
if old_nine in s:
    s = s.replace(old_nine, new_nine)

# Replace synchronous candidate rendering with a generation-checked background pipeline.
pattern = re.compile(r'    private void showPinyinCandidates\(\) \{.*?\n    \}\n\n    private void handleLetterKey', re.S)
replacement = r'''    private void showPinyinCandidates() {
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

    private void handleLetterKey'''
s, count = pattern.subn(replacement, s, count=1)
if count != 1:
    raise RuntimeError('showPinyinCandidates block not found')

# Deletion must also avoid T9 decoding on the UI thread.
old_delete = '''            String composing;\n            if (nineKeyMode) {\n                composing = pinyinEngine == null ? "" : pinyinEngine.displayT9Safe(pinyinBuffer);\n            } else {\n                composing = pinyinEngine == null\n                        ? pinyinBuffer\n                        : pinyinEngine.formatForDisplay(pinyinBuffer);\n            }\n\n            // Never fall back to raw T9 digits. If the shortened sequence is\n            // temporarily ambiguous, keep the previous editor composition alive\n            // and only refresh the candidate UI.\n            if (!composing.isEmpty()) {\n                ic.setComposingText(composing, 1);\n            }\n\n            showPinyinCandidates();'''
new_delete = '''            // V0.10: let the latest-only pipeline refresh composing/candidates.\n            showPinyinCandidates();'''
if old_delete in s:
    s = s.replace(old_delete, new_delete)

# One haptic mechanism per key. The previous double-trigger (framework + vibrator)
# could queue pulses behind rapid typing. We return on successful framework haptic;
# only otherwise do a short cancel-and-replace fallback pulse.
haptic_pattern = re.compile(r'    private void performKeyHaptic\(View view\) \{.*?\n    \}\n\n    private GradientDrawable rounded', re.S)
haptic_replacement = r'''    private void performKeyHaptic(View view) {
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

    private GradientDrawable rounded'''
s, count = haptic_pattern.subn(haptic_replacement, s, count=1)
if count != 1:
    raise RuntimeError('performKeyHaptic block not found')

# Cancel stale candidate generations whenever composition is reset/committed.
s = s.replace(
    '    private void resetPinyinComposition() {\n        pinyinBuffer = "";',
    '    private void resetPinyinComposition() {\n        pinyinGeneration.incrementAndGet();\n        pinyinExecutor.getQueue().clear();\n        pinyinBuffer = "";'
)
s = s.replace(
    '        pinyinBuffer = "";\n        lastPinyinCandidates.clear();\n        if (pinyinCandidatesBar != null) pinyinCandidatesBar.removeAllViews();\n    }\n\n    private void commitBestPinyinCandidateOrRaw()',
    '        pinyinGeneration.incrementAndGet();\n        pinyinExecutor.getQueue().clear();\n        pinyinBuffer = "";\n        lastPinyinCandidates.clear();\n        if (pinyinCandidatesBar != null) pinyinCandidatesBar.removeAllViews();\n    }\n\n    private void commitBestPinyinCandidateOrRaw()'
)

SERVICE.write_text(s, encoding='utf-8')

# Patch engine quick display so the UI-thread path is a small DP over syllable keys,
# never the Beam candidate decoder.
e = ENGINE.read_text(encoding='utf-8')

if 'private final Map<String, String> t9Syllable' not in e:
    e = e.replace(
        '    private final Map<String, T9Token> t9PrefixBest = new HashMap<>();\n',
        '    private final Map<String, T9Token> t9PrefixBest = new HashMap<>();\n'
        '    private final Map<String, String> t9Syllable = new HashMap<>();\n'
    )
    e = e.replace(
        '        t9PrefixBest.clear();\n',
        '        t9PrefixBest.clear();\n        t9Syllable.clear();\n'
    )

# Mark true syllable rows while loading: starter lexicon single-syllable rows have
# at least one single-character candidate and pinyin length <= 6.
needle = '''                if (!words.isEmpty()) {\n                    lexicon.put(py, Collections.unmodifiableList(words));\n                }'''
replace = '''                if (!words.isEmpty()) {\n                    lexicon.put(py, Collections.unmodifiableList(words));\n                    boolean hasSingleChar = false;\n                    for (String w : words) {\n                        if (w.length() == 1) { hasSingleChar = true; break; }\n                    }\n                    if (hasSingleChar && py.length() <= 6) {\n                        String digits = toT9Digits(py);\n                        if (!digits.isEmpty() && !t9Syllable.containsKey(digits)) {\n                            t9Syllable.put(digits, py);\n                        }\n                    }\n                }'''
if needle in e:
    e = e.replace(needle, replace)

# Insert quick display method before displayT9.
if 'public String quickDisplayT9' not in e:
    marker = '    public String displayT9(String digits) {'
    quick = r'''    /** UI-thread safe T9 display. O(n * 6), no Beam search. */
    public String quickDisplayT9(String digits) {
        String d = cleanT9(digits);
        if (d.isEmpty()) return "";

        final int n = d.length();
        String[] best = new String[n + 1];
        int[] syllables = new int[n + 1];
        best[0] = "";
        for (int i = 1; i <= n; i++) syllables[i] = Integer.MAX_VALUE / 4;

        for (int end = 1; end <= n; end++) {
            int startMin = Math.max(0, end - 6);
            for (int start = startMin; start < end; start++) {
                if (best[start] == null) continue;
                String py = t9Syllable.get(d.substring(start, end));
                if (py == null) continue;
                int count = syllables[start] + 1;
                // Prefer fewer syllables; ties prefer the longer final syllable.
                if (best[end] == null || count < syllables[end]
                        || (count == syllables[end] && py.length() > lastPartLength(best[end]))) {
                    best[end] = best[start].isEmpty() ? py : best[start] + " " + py;
                    syllables[end] = count;
                }
            }
        }
        if (best[n] != null) return best[n];

        // Incomplete last syllable: show the best known prefix without doing a search.
        T9Token prefix = t9PrefixBest.get(d);
        return prefix == null ? "" : prefix.pinyin;
    }

    private int lastPartLength(String value) {
        if (value == null || value.isEmpty()) return 0;
        int i = value.lastIndexOf(' ');
        return i < 0 ? value.length() : value.length() - i - 1;
    }

'''
    e = e.replace(marker, quick + marker)

# displayT9Safe is called by legacy paths; make it cheap too.
safe_pattern = re.compile(r'    public String displayT9Safe\(String digits\) \{.*?\n    \}\n\n    public boolean hasT9Prefix', re.S)
safe_replacement = r'''    public String displayT9Safe(String digits) {
        return quickDisplayT9(digits);
    }

    public boolean hasT9Prefix'''
e, count = safe_pattern.subn(safe_replacement, e, count=1)
if count != 1:
    raise RuntimeError('displayT9Safe block not found')

ENGINE.write_text(e, encoding='utf-8')
print('V0.10 input core patch applied')
