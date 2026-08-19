package com.jingwei.aikeyboard;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight local pinyin lookup engine.
 *
 * The lexicon is generated from the Rime luna_pinyin dictionary and
 * rime-essay-simp vocabulary. It runs completely offline and is intentionally
 * wrapped behind this class so a native librime backend can replace it later
 * without changing the keyboard UI.
 */
public final class PinyinEngine {
    private final Context context;
    private final Map<String, List<String>> lexicon = new HashMap<>();
    private final Map<String, List<String>> t9Lexicon = new HashMap<>();
    private final Map<String, String> t9Pinyin = new HashMap<>();

    private final Map<String, List<T9Token>> t9Tokens = new HashMap<>();

    private static final class T9Token {
        final String pinyin;
        final String word;
        final int frequency;

        T9Token(String pinyin, String word, int frequency) {
            this.pinyin = pinyin;
            this.word = word;
            this.frequency = frequency;
        }
    }

    private static final class T9State {
        final double score;
        final String text;
        final String pinyin;

        T9State(double score, String text, String pinyin) {
            this.score = score;
            this.text = text;
            this.pinyin = pinyin;
        }
    }

    private volatile boolean loaded = false;

    public PinyinEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isLoaded() {
        return loaded;
    }

    public synchronized void load() throws Exception {
        if (loaded) return;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                context.getAssets().open("pinyin_lexicon.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab >= line.length() - 1) continue;
                String key = line.substring(0, tab);
                String[] words = line.substring(tab + 1).split("\\|");
                List<String> wordList = Collections.unmodifiableList(Arrays.asList(words));
                lexicon.put(key, wordList);

                String digits = toT9Digits(key);
                if (!digits.isEmpty()) {
                    List<String> existing = t9Lexicon.get(digits);
                    if (existing == null) {
                        existing = new ArrayList<>();
                        t9Lexicon.put(digits, existing);
                    }
                    for (String word : words) {
                        if (!existing.contains(word)) existing.add(word);
                        if (existing.size() >= 20) break;
                    }
                    if (!t9Pinyin.containsKey(digits)) t9Pinyin.put(digits, key);
                }
            }
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                context.getAssets().open("t9_tokens.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] parts = line.split("\t");
                if (parts.length < 4) continue;
                String digits = parts[0];
                String py = parts[1];
                String word = parts[2];
                int freq = 1;
                try { freq = Integer.parseInt(parts[3]); } catch (Throwable ignored) {}
                List<T9Token> list = t9Tokens.get(digits);
                if (list == null) {
                    list = new ArrayList<>();
                    t9Tokens.put(digits, list);
                }
                list.add(new T9Token(py, word, Math.max(1, freq)));
            }
        }

        loaded = true;
    }

    public List<String> search(String rawPinyin, int limit) {
        String key = normalize(rawPinyin);
        if (key.isEmpty()) return Collections.emptyList();

        List<String> exact = lexicon.get(key);
        if (exact != null && !exact.isEmpty()) {
            int n = Math.min(Math.max(limit, 1), exact.size());
            return new ArrayList<>(exact.subList(0, n));
        }

        // 连续拼音：例如 jintianzenmeyang -> jintian + zenmeyang -> 今天怎么样
        List<String> segmented = segmentCandidates(key, limit);
        if (!segmented.isEmpty()) return segmented;

        List<String> initials = initialFallback(rawPinyin, limit);
        if (!initials.isEmpty()) return initials;

        return fallback(key, limit);
    }


    /** Frequency-ranked 9-key/T9 decoder. */
    public List<String> searchT9(String digits, int limit) {
        String d = cleanT9(digits);
        if (d.isEmpty()) return Collections.emptyList();

        List<T9State> decoded = decodeT9States(d, Math.max(8, limit));
        if (!decoded.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (T9State state : decoded) {
                if (!out.contains(state.text)) out.add(state.text);
                if (out.size() >= limit) break;
            }
            if (!out.isEmpty()) return out;
        }

        // Incomplete trailing syllable: show prefix candidates instead of nonsense.
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<T9Token>> e : t9Tokens.entrySet()) {
            if (!e.getKey().startsWith(d)) continue;
            for (T9Token token : e.getValue()) {
                if (!out.contains(token.word)) out.add(token.word);
                if (out.size() >= limit) return out;
            }
        }
        return out;
    }

    /** Best pinyin spelling for the current 9-key sequence. */
    public String displayT9(String digits) {
        String d = cleanT9(digits);
        if (d.isEmpty()) return "";

        List<T9State> states = decodeT9States(d, 1);
        if (!states.isEmpty()) return states.get(0).pinyin;

        // If the last syllable is unfinished, prefer the most frequent matching token.
        T9Token best = null;
        for (Map.Entry<String, List<T9Token>> e : t9Tokens.entrySet()) {
            if (!e.getKey().startsWith(d) || e.getValue().isEmpty()) continue;
            T9Token candidate = e.getValue().get(0);
            if (best == null || candidate.frequency > best.frequency) best = candidate;
        }
        return best == null ? "" : best.pinyin;
    }

    public boolean hasT9Prefix(String digits) {
        String d = cleanT9(digits);
        if (d.isEmpty()) return true;
        if (!decodeT9States(d, 1).isEmpty()) return true;
        for (String key : t9Tokens.keySet()) {
            if (key.startsWith(d)) return true;
        }
        return true; // never block long-sentence typing
    }

    private String cleanT9(String digits) {
        if (digits == null) return "";
        return digits.replaceAll("[^2-9]", "");
    }

    private List<T9State> decodeT9States(String digits, int limit) {
        final int n = digits.length();
        final int maxChunk = 16;
        final int beamWidth = 90;
        final double tokenPenalty = 18.0;

        Map<Integer, List<T9State>> beams = new HashMap<>();
        List<T9State> start = new ArrayList<>();
        start.add(new T9State(0.0, "", ""));
        beams.put(0, start);

        for (int pos = 0; pos < n; pos++) {
            List<T9State> current = beams.get(pos);
            if (current == null || current.isEmpty()) continue;
            current = topStates(current, beamWidth);

            int maxEnd = Math.min(n, pos + maxChunk);
            for (int end = pos + 1; end <= maxEnd; end++) {
                String piece = digits.substring(pos, end);
                List<T9Token> tokens = t9Tokens.get(piece);
                if (tokens == null || tokens.isEmpty()) continue;

                int tokenCap = Math.min(tokens.size(), 28);
                List<T9State> target = beams.get(end);
                if (target == null) {
                    target = new ArrayList<>();
                    beams.put(end, target);
                }

                for (int ti = 0; ti < tokenCap; ti++) {
                    T9Token token = tokens.get(ti);
                    double add = Math.log(token.frequency + 1.0) - tokenPenalty - ti * 0.02;
                    if (token.pinyin.length() == 1) add -= 2.0;

                    for (T9State state : current) {
                        String nextText = state.text + token.word;
                        String nextPy = state.pinyin.isEmpty()
                                ? token.pinyin
                                : state.pinyin + " " + token.pinyin;
                        target.add(new T9State(state.score + add, nextText, nextPy));
                    }
                }

                if (target.size() > beamWidth * 4) {
                    beams.put(end, topStates(target, beamWidth));
                }
            }
        }

        List<T9State> finals = beams.get(n);
        if (finals == null || finals.isEmpty()) return Collections.emptyList();
        return topStates(finals, Math.max(limit * 4, limit));
    }

    private List<T9State> topStates(List<T9State> input, int count) {
        Collections.sort(input, (a, b) -> Double.compare(b.score, a.score));
        List<T9State> out = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();

        for (T9State state : input) {
            // Keep distinct Chinese outputs; equivalent segmentations are redundant.
            if (seen.containsKey(state.text)) continue;
            seen.put(state.text, true);
            out.add(state);
            if (out.size() >= count) break;
        }
        return out;
    }

    public String displayT9Safe(String digits) {
        String value = displayT9(digits);
        if (value != null && !value.isEmpty()) return value;

        if (digits == null) return "";
        String d = digits.replaceAll("[^2-9]", "");
        if (d.isEmpty()) return "";

        // Find the longest decodable prefix, then show only that pinyin.
        // The undecoded tail stays internal, never as raw numbers.
        for (int cut = d.length() - 1; cut > 0; cut--) {
            String prefix = d.substring(0, cut);
            String decoded = displayT9(prefix);
            if (decoded != null && !decoded.isEmpty()) return decoded;
        }
        return "";
    }

    private String splitPinyinForDisplay(String pinyin) {
        // Lightweight display formatting. The lexicon already contains valid pinyin;
        // keeping it compact avoids a separate blank composition row.
        return pinyin == null ? "" : pinyin;
    }

    private String toT9Digits(String pinyin) {
        if (pinyin == null) return "";
        StringBuilder sb = new StringBuilder();
        String v = normalize(pinyin);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if ("abc".indexOf(c) >= 0) sb.append('2');
            else if ("def".indexOf(c) >= 0) sb.append('3');
            else if ("ghi".indexOf(c) >= 0) sb.append('4');
            else if ("jkl".indexOf(c) >= 0) sb.append('5');
            else if ("mno".indexOf(c) >= 0) sb.append('6');
            else if ("pqrs".indexOf(c) >= 0) sb.append('7');
            else if ("tuv".indexOf(c) >= 0) sb.append('8');
            else if ("wxyz".indexOf(c) >= 0) sb.append('9');
        }
        return sb.toString();
    }

    private List<String> segmentCandidates(String key, int limit) {
        Map<Integer, List<String>> memo = new HashMap<>();
        return segmentFrom(key, 0, Math.max(1, limit), memo);
    }

    private List<String> segmentFrom(String key, int pos, int limit, Map<Integer, List<String>> memo) {
        if (pos == key.length()) return new ArrayList<>(Collections.singletonList(""));
        if (memo.containsKey(pos)) return memo.get(pos);
        List<String> out = new ArrayList<>();
        // 优先长词，减少把完整词组拆成单字。
        for (int end = key.length(); end > pos; end--) {
            String part = key.substring(pos, end);
            List<String> words = lexicon.get(part);
            if (words == null || words.isEmpty()) continue;
            List<String> tails = segmentFrom(key, end, limit, memo);
            if (tails.isEmpty()) continue;
            int wordCount = Math.min(2, words.size());
            for (int w = 0; w < wordCount; w++) {
                for (String tail : tails) {
                    String candidate = words.get(w) + tail;
                    if (!out.contains(candidate)) out.add(candidate);
                    if (out.size() >= limit) { memo.put(pos, out); return out; }
                }
            }
        }
        memo.put(pos, out);
        return out;
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase()
                .replace(" ", "")
                .replace("'", "")
                .replace("ü", "v")
                .trim();
    }

    /**
     * Display composing pinyin in the same candidate row, Baidu-style.
     * Explicit apostrophes are shown as spaces: ni'hao -> ni hao.
     */
    public String formatForDisplay(String rawPinyin) {
        if (rawPinyin == null) return "";
        String value = rawPinyin.toLowerCase()
                .replace("ü", "v")
                .replaceAll("\\s+", "")
                .trim();
        return value.replace("'", " ").replaceAll(" +", " ");
    }

    /**
     * Lightweight abbreviated-pinyin support.
     * Examples:
     *   nh      -> 你好
     *   hhhhh   -> 哈哈哈哈哈
     *   h'h'h'h'h -> 哈哈哈哈哈
     *
     * This is intentionally a small, predictable layer. A native Rime/librime
     * backend can replace it later without changing the keyboard UI.
     */
    private List<String> initialFallback(String raw, int limit) {
        if (raw == null) return Collections.emptyList();
        String cleaned = raw.toLowerCase()
                .replace(" ", "")
                .replace("'", "")
                .replace("ü", "v")
                .trim();
        if (cleaned.isEmpty()) return Collections.emptyList();

        // Only treat consonant-only input as "简拼", so normal full pinyin
        // such as nihao/woshi is never disturbed.
        if (!cleaned.matches("[bcdfghjklmnpqrstvwxyz]+")) {
            return Collections.emptyList();
        }

        List<String> out = new ArrayList<>();

        // High-frequency shorthand phrases.
        Map<String, String[]> common = new HashMap<>();
        common.put("nh", new String[]{"你好", "你会", "你还"});
        common.put("wm", new String[]{"我们", "外面"});
        common.put("ws", new String[]{"我是", "晚上", "为啥"});
        common.put("xw", new String[]{"希望", "下午", "新闻"});
        common.put("xiex", new String[]{"谢谢"});
        common.put("hh", new String[]{"哈哈", "还好"});
        common.put("hhh", new String[]{"哈哈哈", "还好哈"});
        common.put("hhhh", new String[]{"哈哈哈哈", "还好还好"});
        common.put("hhhhh", new String[]{"哈哈哈哈哈", "还好还好哈"});
        String[] phrases = common.get(cleaned);
        if (phrases != null) {
            for (String phrase : phrases) {
                if (!out.contains(phrase)) out.add(phrase);
                if (out.size() >= limit) return out;
            }
        }

        // Repeated single initial: hhhhh -> 哈哈哈哈哈.
        if (cleaned.length() >= 2) {
            char first = cleaned.charAt(0);
            boolean same = true;
            for (int i = 1; i < cleaned.length(); i++) {
                if (cleaned.charAt(i) != first) { same = false; break; }
            }
            if (same) {
                String ch = commonInitialChar(first);
                if (!ch.isEmpty()) {
                    StringBuilder repeated = new StringBuilder();
                    for (int i = 0; i < cleaned.length(); i++) repeated.append(ch);
                    String candidate = repeated.toString();
                    if (!out.contains(candidate)) out.add(0, candidate);
                }
            }
        }

        return out.size() <= limit ? out : new ArrayList<>(out.subList(0, limit));
    }

    private String commonInitialChar(char c) {
        switch (c) {
            case 'h': return "哈";
            case 'n': return "你";
            case 'w': return "我";
            case 's': return "是";
            case 'x': return "想";
            case 'y': return "有";
            case 'z': return "在";
            case 'b': return "不";
            case 'k': return "可";
            case 'd': return "的";
            case 'm': return "吗";
            case 'q': return "去";
            case 'l': return "了";
            case 'r': return "人";
            case 'j': return "就";
            case 'g': return "个";
            case 'c': return "才";
            case 't': return "他";
            case 'f': return "发";
            case 'p': return "朋";
            default: return "";
        }
    }

    /** Tiny safety net while the big lexicon is still loading. */
    private List<String> fallback(String key, int limit) {
        String[] words;
        switch (key) {
            case "ni": words = new String[]{"你", "呢", "妮", "泥", "拟"}; break;
            case "hao": words = new String[]{"好", "号", "浩", "豪", "耗"}; break;
            case "nihao": words = new String[]{"你好"}; break;
            case "wo": words = new String[]{"我", "握", "窝", "卧"}; break;
            case "shi": words = new String[]{"是", "时", "事", "市", "十", "使"}; break;
            case "woshi": words = new String[]{"我是"}; break;
            case "de": words = new String[]{"的", "得", "德"}; break;
            case "le": words = new String[]{"了", "乐", "勒"}; break;
            case "ma": words = new String[]{"吗", "妈", "马", "嘛"}; break;
            case "keyi": words = new String[]{"可以"}; break;
            case "xiexie": words = new String[]{"谢谢"}; break;
            default: return Collections.emptyList();
        }
        return new ArrayList<>(Arrays.asList(words).subList(0, Math.min(limit, words.length)));
    }
}
