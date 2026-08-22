from pathlib import Path

path = Path('app/src/main/java/com/jingwei/aikeyboard/JingweiImeService.java')
s = path.read_text(encoding='utf-8')


def must_replace(old, new, count=1):
    global s
    if old not in s:
        raise SystemExit('pattern not found:\n' + old[:240])
    s = s.replace(old, new, count)

must_replace(
    'import java.util.concurrent.Executors;\nimport android.widget.Space;',
    'import java.util.concurrent.Executors;\nimport java.util.concurrent.atomic.AtomicInteger;\nimport android.widget.Space;'
)

must_replace(
    '    private final ExecutorService executor = Executors.newSingleThreadExecutor();\n    private final Handler main = new Handler(Looper.getMainLooper());',
    '    private final ExecutorService executor = Executors.newSingleThreadExecutor();\n'
    '    // V0.10: pinyin decoding gets its own worker so AI/network work can never block typing.\n'
    '    private final ExecutorService pinyinExecutor = Executors.newSingleThreadExecutor();\n'
    '    private final AtomicInteger pinyinGeneration = new AtomicInteger(0);\n'
    '    private final Handler main = new Handler(Looper.getMainLooper());\n'
    '    private Vibrator keyVibrator;'
)

must_replace(
    '        pinyinEngine = new PinyinEngine(this);\n        executor.execute(() -> {',
    '        keyVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);\n\n'
    '        pinyinEngine = new PinyinEngine(this);\n        pinyinExecutor.execute(() -> {'
)

old_haptic = '''    private void performKeyHaptic(View view) {
        // First try Android's keyboard tap feedback.
        try {
            if (view != null) {
                view.performHapticFeedback(
                        HapticFeedbackConstants.KEYBOARD_TAP,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            }
        } catch (Throwable ignored) {}

        // Vendor ROM fallback (ColorOS/OxygenOS etc.): a very short, low-amplitude pulse.
        try {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(10, 55));
            } else {
                vibrator.vibrate(10);
            }
        } catch (Throwable ignored) {}
    }
'''
new_haptic = '''    private void performKeyHaptic(View view) {
        // V0.10 low-latency haptics: use ONE pulse path only. The old implementation
        // fired system haptics + Vibrator together, which could queue on fast typing.
        try {
            if (keyVibrator == null) {
                keyVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (keyVibrator != null && keyVibrator.hasVibrator()) {
                keyVibrator.cancel(); // newest key wins; never let old pulses queue up
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    keyVibrator.vibrate(VibrationEffect.createOneShot(6, 72));
                } else {
                    keyVibrator.vibrate(6);
                }
                return;
            }
        } catch (Throwable ignored) {}

        // Fallback only when a vibrator service is unavailable.
        try {
            if (view != null) {
                view.performHapticFeedback(
                        HapticFeedbackConstants.KEYBOARD_TAP,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            }
        } catch (Throwable ignored) {}
    }
'''
must_replace(old_haptic, new_haptic)

old_t9 = '''    private void handleNineKeyDigit(String digit) {
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

        String composing = pinyinEngine == null ? "" : pinyinEngine.displayT9Safe(pinyinBuffer);

        // If the decoder has a pinyin spelling, use composing text.
        // If it doesn't yet, keep the editor untouched; the candidate UI can still update.
        if (!composing.isEmpty()) {
            ic.setComposingText(composing, 1);
        }

        showPinyinCandidates();
    }
'''
new_t9 = '''    private void handleNineKeyDigit(String digit) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        if (!chineseMode) {
            ic.commitText(digit, 1);
            return;
        }

        // V0.10: the tap path is intentionally O(1). Do not decode T9 on the UI thread.
        if (pinyinBuffer.length() >= 64) return;
        pinyinBuffer += digit;
        showPinyinCandidates();
    }
'''
must_replace(old_t9, new_t9)

start = s.index('    private void showPinyinCandidates() {')
end = s.index('    private void handleLetterKey(String letter) {', start)
new_candidates = '''    private void showPinyinCandidates() {
        if (pinyinCandidatesBar == null) return;

        final int generation = pinyinGeneration.incrementAndGet();
        final String buffer = pinyinBuffer;
        final boolean nine = nineKeyMode;

        pinyinCandidatesBar.removeAllViews();
        lastPinyinCandidates = new ArrayList<>();

        if (!chineseMode || buffer.isEmpty()) {
            if (composingRow != null) composingRow.setVisibility(View.GONE);
            if (pinyinStatus != null) {
                pinyinStatus.setVisibility(View.VISIBLE);
                pinyinStatus.setText(chineseMode ? "中" : "EN");
            }
            return;
        }

        // Immediate UI feedback: never wait for dictionary/T9 decoding before the key feels accepted.
        if (composingRow != null) composingRow.setVisibility(View.VISIBLE);
        if (pinyinStatus != null) pinyinStatus.setVisibility(View.GONE);
        if (composingSplitButton != null) {
            composingSplitButton.setVisibility(nine ? View.VISIBLE : View.GONE);
        }
        if (composingText != null) {
            composingText.setText(nine ? "…" : buffer.replace("'", " "));
        }

        pinyinExecutor.execute(() -> {
            String display;
            List<String> words;
            try {
                if (pinyinEngine == null) {
                    display = nine ? "" : buffer.replace("'", " ");
                    words = new ArrayList<>();
                } else if (nine) {
                    display = pinyinEngine.displayT9Safe(buffer);
                    words = pinyinEngine.searchT9(buffer, 10);
                } else {
                    display = pinyinEngine.formatForDisplay(buffer);
                    words = pinyinEngine.search(buffer, 10);
                }
            } catch (Throwable ignored) {
                display = nine ? "" : buffer.replace("'", " ");
                words = new ArrayList<>();
            }

            final String resolvedDisplay = display;
            final List<String> resolvedWords = new ArrayList<>(words);
            main.post(() -> {
                // Drop stale work. Fast typing may create many requests; only newest result can touch UI.
                if (generation != pinyinGeneration.get()) return;
                if (!buffer.equals(pinyinBuffer) || nine != nineKeyMode) return;

                if (composingText != null) {
                    composingText.setText(resolvedDisplay.isEmpty() ? (nine ? "…" : buffer) : resolvedDisplay);
                }
                if (nine && !resolvedDisplay.isEmpty()) {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) ic.setComposingText(resolvedDisplay, 1);
                }
                renderPinyinCandidates(resolvedWords);
            });
        });
    }

    private void renderPinyinCandidates(List<String> words) {
        if (pinyinCandidatesBar == null) return;
        pinyinCandidatesBar.removeAllViews();
        lastPinyinCandidates = new ArrayList<>(words);
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

'''
s = s[:start] + new_candidates + s[end:]

old_delete = '''            String composing;
            if (nineKeyMode) {
                composing = pinyinEngine == null ? "" : pinyinEngine.displayT9Safe(pinyinBuffer);
            } else {
                composing = pinyinEngine == null
                        ? pinyinBuffer
                        : pinyinEngine.formatForDisplay(pinyinBuffer);
            }

            // Never fall back to raw T9 digits. If the shortened sequence is
            // temporarily ambiguous, keep the previous editor composition alive
            // and only refresh the candidate UI.
            if (!composing.isEmpty()) {
                ic.setComposingText(composing, 1);
            }

            showPinyinCandidates();
'''
new_delete = '''            if (!nineKeyMode) {
                String composing = pinyinEngine == null
                        ? pinyinBuffer
                        : pinyinEngine.formatForDisplay(pinyinBuffer);
                if (!composing.isEmpty()) ic.setComposingText(composing, 1);
            }
            // 9-key decoding is asynchronous in V0.10; never block Backspace on T9 search.
            showPinyinCandidates();
'''
must_replace(old_delete, new_delete)

must_replace(
    '        deleting = false;\n        main.removeCallbacks(deleteRunnable);\n        if (speechRecognizer != null) speechRecognizer.destroy();\n        executor.shutdownNow();',
    '        deleting = false;\n        main.removeCallbacks(deleteRunnable);\n        pinyinGeneration.incrementAndGet();\n        if (speechRecognizer != null) speechRecognizer.destroy();\n        try { if (keyVibrator != null) keyVibrator.cancel(); } catch (Throwable ignored) {}\n        pinyinExecutor.shutdownNow();\n        executor.shutdownNow();'
)

path.write_text(s, encoding='utf-8')
print('V0.10 input-core patch applied')
