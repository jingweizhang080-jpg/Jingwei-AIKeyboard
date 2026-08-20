package com.jingwei.aikeyboard;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight offline pinyin/T9 engine.
 *
 * V0.8 performance goals:
 * 1) never scan the whole T9 dictionary on every key press;
 * 2) reuse one decode result for display + candidate rendering;
 * 3) keep the beam small enough for continuous typing on mid-range phones;
 * 4) use dictionary frequency for general phrases instead of hard-coding only a few examples.
 *
 * This remains a lightweight Java engine. A native Rime/librime backend can replace it later
 * without changing JingweiImeService's public calls.
 */
public final class PinyinEngine {
    private final Context context;
    private final Map<String, List<String>> lexicon = new HashMap<>();
    private final Map<String, List<T9Token>> t9Tokens = new HashMap<>();

    /** Best token for every numeric prefix, built once during load(). */
    private final Map<String, T9Token> prefixBest = new HashMap<>();

    /** Tiny LRU caches: one fast decode is shared by displayT9Safe() and searchT9(). */
    private final Map<String, List<T9State>> t9DecodeCache = lruMap(96);
    private final Map<String, List<String>> searchCache = lruMap(96);

    private volatile boolean loaded = false;

    private static final int BEAM_WIDTH = 24;
    private static final int TOKEN_CAP = 10;
    private static final int MAX_TOKEN_DIGITS = 18;

    private static final class T9Token {
        final String pinyin;
        final String word;
        final int frequency;

        T9Token(String pinyin, String word, int frequency) {
            this.pinyin = pinyin;
            this.word = word;
            this.frequency = Math.max(1, frequency);
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

    private static <K, V> Map<K, V> lruMap(final int max) {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>(max, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > max;
            }
        });
    }

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
                String key = normalize(line.substring(0, tab));
                String[] words = line.substring(tab + 1).split("\\|");
                lexicon.put(key, Collections.unmodifiableList(Arrays.asList(words)));
            }
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                context.getAssets().open("t9_tokens.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] parts = line.split("\t");
                if (parts.length < 4) continue;
                String digits = cleanT9(parts[0]);
                if (digits.isEmpty()) continue;
                String py = parts[1];
                String word = parts[2];
                int freq = 1;
                try { freq = Integer.parseInt(parts[3]); } catch (Throwable ignored) {}

                T9Token token = new T9Token(py, word, freq);
                List<T9Token> list = t9Tokens.get(digits);
                if (list == null) {
                    list = new ArrayList<>();
                    t9Tokens.put(digits, list);
                }
                list.add(token);

                // Pre-index prefixes once. This replaces the old O(dictionary-size)
                // scan that ran repeatedly while the user was tapping quickly.
                int prefixLimit = Math.min(digits.length(), MAX_TOKEN_DIGITS);
                for (int i = 1; i <= prefixLimit; i++) {
                    String prefix = digits.substring(0, i);
                    T9Token old = prefixBest.get(prefix);
                    if (old == null || betterPrefixToken(token, old)) {
                        prefixBest.put(prefix, token);
                    }
                }
            }
        }

        // Keep high-frequency choices first so each beam expansion can inspect only TOKEN_CAP.
        Comparator<T9Token> byFreq = (a, b) -> Integer.compare(b.frequency, a.frequency);
        for (List<T9Token> list : t9Tokens.values()) {
            Collections.sort(list, byFreq);
        }

        loaded = true;
    }

    private boolean betterPrefixToken(T9Token a, T9Token b) {
        if (a.frequency != b.frequency) return a.frequency > b.frequency;
        if (a.word.length() != b.word.length()) return a.word.length() > b.word.length();
        return a.pinyin.length() < b.pinyin.length();
    }

    // -------------------- 26-key --------------------

    public List<String> search(String rawPinyin, int limit) {
        String key = normalize(rawPinyin);
        if (key.isEmpty()) return Collections.emptyList();

        List<String> exact = lexicon.get(key);
        if (exact != null && !exact.isEmpty()) {
            return copyLimit(exact, limit);
        }

        List<String> segmented = segmentCandidates(key, limit);
        if (!segmented.isEmpty()) return segmented;

        List<String> initials = initialFallback(rawPinyin, limit);
        if (!initials.isEmpty()) return initials;

        return fallback(key, limit);
    }

    // -------------------- 9-key / T9 --------------------

    public List<String> searchT9(String digits, int limit) {
        String d = cleanT9(digits);
        if (d.isEmpty()) return Collections.emptyList();

        String cacheKey = d + "#" + Math.max(1, limit);
        List<String> cached = searchCache.get(cacheKey);
        if (cached != null) return new ArrayList<>(cached);

        List<String> out = new ArrayList<>();

        // Exact whole-token hits are cheap and often very good for words/phrases.
        List<T9Token> direct = t9Tokens.get(d);
        if (direct != null) {
            int n = Math.min(direct.size(), Math.min(limit, TOKEN_CAP));
            for (int i = 0; i < n; i++) addUnique(out, direct.get(i).word, limit);
        }

        // General sentence decoding. No special-casing is required for every phrase.
        for (T9State state : decodedStates(d)) {
            addUnique(out, state.text, limit);
            if (out.size() >= limit) break;
        }

        // If the last syllable is incomplete, O(1) prefix lookup keeps candidates responsive.
        if (out.size() < limit) {
            T9Token prefix = prefixBest.get(d);
            if (prefix != null) addUnique(out, prefix.word, limit);
        }

        List<String> saved = Collections.unmodifiableList(new ArrayList<>(out));
        searchCache.put(cacheKey, saved);
        return new ArrayList<>(saved);
    }

    public String displayT9(String digits) {
        String d = cleanT9(digits);
        if (d.isEmpty()) return "";

        List<T9State> states = decodedStates(d);
        if (!states.isEmpty()) return states.get(0).pinyin;

        T9Token prefix = prefixBest.get(d);
        return prefix == null ? "" : prefix.pinyin;
    }

    public String displayT9Safe(String digits) {
        String d = cleanT9(digits);
        if (d.isEmpty()) return "";

        String value = displayT9(d);
        if (!value.isEmpty()) return value;

        // Prefix lookup is precomputed, so this remains cheap during rapid tapping.
        for (int cut = d.length() - 1; cut > 0; cut--) {
            T9Token token = prefixBest.get(d.substring(0, cut));
            if (token != null) return token.pinyin;
        }
        return "";
    }

    public boolean hasT9Prefix(String digits) {
        String d = cleanT9(digits);
        return d.isEmpty() || prefixBest.containsKey(d) || !decodedStates(d).isEmpty();
    }

    private List<T9State> decodedStates(String digits) {
        List<T9State> cached = t9DecodeCache.get(digits);
        if (cached != null) return cached;

        List<T9State> decoded = decodeT9States(digits);
        List<T9State> saved = Collections.unmodifiableList(decoded);
        t9DecodeCache.put(digits, saved);
        return saved;
    }

    private List<T9State> decodeT9States(String digits) {
        final int n = digits.length();
        if (n == 0) return Collections.emptyList();

        Map<Integer, List<T9State>> beams = new HashMap<>();
        beams.put(0, new ArrayList<>(Collections.singletonList(new T9State(0.0, "", ""))));

        for (int pos = 0; pos < n; pos++) {
            List<T9State> current = beams.get(pos);
            if (current == null || current.isEmpty()) continue;
            current = topStates(current, BEAM_WIDTH);

            int maxEnd = Math.min(n, pos + MAX_TOKEN_DIGITS);
            for (int end = pos + 1; end <= maxEnd; end++) {
                List<T9Token> tokens = t9Tokens.get(digits.substring(pos, end));
                if (tokens == null || tokens.isEmpty()) continue;

                List<T9State> target = beams.get(end);
                if (target == null) {
                    target = new ArrayList<>();
                    beams.put(end, target);
                }

                int cap = Math.min(tokens.size(), TOKEN_CAP);
                for (int ti = 0; ti < cap; ti++) {
                    T9Token token = tokens.get(ti);
                    double add = tokenScore(token, ti);
                    for (T9State state : current) {
                        target.add(new T9State(
                                state.score + add,
                                state.text + token.word,
                                state.pinyin.isEmpty() ? token.pinyin : state.pinyin + " " + token.pinyin));
                    }
                }

                // Bound memory/CPU aggressively during long rapid input.
                if (target.size() > BEAM_WIDTH * 3) {
                    beams.put(end, topStates(target, BEAM_WIDTH));
                }
            }
        }

        List<T9State> finals = beams.get(n);
        if (finals == null || finals.isEmpty()) return Collections.emptyList();
        return topStates(finals, BEAM_WIDTH);
    }

    private double tokenScore(T9Token token, int rank) {
        // Frequency is primary. A moderate multi-character bonus helps real words/phrases
        // beat unlikely chains of unrelated single characters without the old huge penalty.
        double freq = Math.log(token.frequency + 1.0);
        double lengthBonus = Math.min(4, token.word.length()) * 0.85;
        double segmentationCost = 1.15;
        return freq + lengthBonus - segmentationCost - rank * 0.035;
    }

    private List<T9State> topStates(List<T9State> input, int count) {
        if (input.size() > 1) {
            Collections.sort(input, (a, b) -> Double.compare(b.score, a.score));
        }
        List<T9State> out = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        for (T9State state : input) {
            if (seen.containsKey(state.text)) continue;
            seen.put(state.text, true);
            out.add(state);
            if (out.size() >= count) break;
        }
        return out;
    }

    private void addUnique(List<String> out, String value, int limit) {
        if (value == null || value.isEmpty() || out.contains(value) || out.size() >= limit) return;
        out.add(value);
    }

    private String cleanT9(String digits) {
        if (digits == null) return "";
        return digits.replaceAll("[^2-9]", "");
    }

    private String toT9Digits(String pinyin) {
        String v = normalize(pinyin);
        StringBuilder sb = new StringBuilder(v.length());
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

    // -------------------- shared helpers --------------------

    private List<String> segmentCandidates(String key, int limit) {
        Map<Integer, List<String>> memo = new HashMap<>();
        return segmentFrom(key, 0, Math.max(1, limit), memo);
    }

    private List<String> segmentFrom(String key, int pos, int limit, Map<Integer, List<String>> memo) {
        if (pos == key.length()) return new ArrayList<>(Collections.singletonList(""));
        List<String> saved = memo.get(pos);
        if (saved != null) return saved;

        List<String> out = new ArrayList<>();
        for (int end = key.length(); end > pos; end--) {
            List<String> words = lexicon.get(key.substring(pos, end));
            if (words == null || words.isEmpty()) continue;
            List<String> tails = segmentFrom(key, end, limit, memo);
            if (tails.isEmpty()) continue;
            int wordCount = Math.min(2, words.size());
            for (int w = 0; w < wordCount; w++) {
                for (String tail : tails) {
                    String candidate = words.get(w) + tail;
                    if (!out.contains(candidate)) out.add(candidate);
                    if (out.size() >= limit) {
                        memo.put(pos, out);
                        return out;
                    }
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

    public String formatForDisplay(String rawPinyin) {
        if (rawPinyin == null) return "";
        String value = rawPinyin.toLowerCase()
                .replace("ü", "v")
                .replaceAll("\\s+", "")
                .trim();
        return value.replace("'", " ").replaceAll(" +", " ");
    }

    private List<String> initialFallback(String raw, int limit) {
        if (raw == null) return Collections.emptyList();
        String cleaned = raw.toLowerCase()
                .replace(" ", "")
                .replace("'", "")
                .replace("ü", "v")
                .trim();
        if (cleaned.isEmpty() || !cleaned.matches("[bcdfghjklmnpqrstvwxyz]+")) {
            return Collections.emptyList();
        }

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

        List<String> out = new ArrayList<>();
        String[] phrases = common.get(cleaned);
        if (phrases != null) {
            for (String phrase : phrases) addUnique(out, phrase, limit);
        }

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
                    if (!out.contains(repeated.toString())) out.add(0, repeated.toString());
                }
            }
        }
        return copyLimit(out, limit);
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
        return copyLimit(Arrays.asList(words), limit);
    }

    private List<String> copyLimit(List<String> source, int limit) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        int n = Math.min(Math.max(1, limit), source.size());
        return new ArrayList<>(source.subList(0, n));
    }
}
