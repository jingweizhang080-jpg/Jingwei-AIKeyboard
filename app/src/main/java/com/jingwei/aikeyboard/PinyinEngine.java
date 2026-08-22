package com.jingwei.aikeyboard;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V0.9 lightweight offline pinyin/T9 engine.
 *
 * Important design change:
 * - pinyin_lexicon.tsv is now the single source of truth.
 * - T9 indexes are derived from that lexicon at load time, so an empty
 *   t9_tokens.tsv can no longer make 9-key input effectively unusable.
 * - decoding is intentionally small/cached to protect the IME UI thread.
 *
 * This is still a temporary bridge engine. The long-term production target is
 * a mature Rime/librime backend, but this implementation keeps V0.9 testable
 * while that native engine is integrated.
 */
public final class PinyinEngine {
    private final Context context;

    private final Map<String, List<String>> lexicon = new HashMap<>();
    private final Map<String, List<T9Token>> t9Exact = new HashMap<>();
    private final Map<String, T9Token> t9PrefixBest = new HashMap<>();

    private final Map<String, List<String>> searchCache = lruMap(160);
    private final Map<String, List<T9State>> t9Cache = lruMap(160);

    private volatile boolean loaded = false;

    private static final int BEAM_WIDTH = 10;
    private static final int TOKEN_CAP = 5;
    private static final int MAX_TOKEN_DIGITS = 12;

    private static final class T9Token {
        final String pinyin;
        final String word;
        final int score;

        T9Token(String pinyin, String word, int score) {
            this.pinyin = pinyin;
            this.word = word;
            this.score = score;
        }
    }

    private static final class T9State {
        final int score;
        final String text;
        final String pinyin;

        T9State(int score, String text, String pinyin) {
            this.score = score;
            this.text = text;
            this.pinyin = pinyin;
        }
    }

    private static <K, V> Map<K, V> lruMap(final int max) {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>(max, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
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

        lexicon.clear();
        t9Exact.clear();
        t9PrefixBest.clear();
        searchCache.clear();
        t9Cache.clear();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                context.getAssets().open("pinyin_lexicon.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                int tab = line.indexOf('\t');
                if (tab <= 0 || tab >= line.length() - 1) continue;

                String py = normalize(line.substring(0, tab));
                if (py.isEmpty()) continue;

                String[] rawWords = line.substring(tab + 1).split("\\|");
                ArrayList<String> words = new ArrayList<>();
                int rank = 0;
                for (String raw : rawWords) {
                    String word = raw.trim();
                    if (word.isEmpty() || words.contains(word)) continue;
                    words.add(word);
                    indexT9(py, word, rank++);
                }
                if (!words.isEmpty()) {
                    lexicon.put(py, Collections.unmodifiableList(words));
                }
            }
        }

        loaded = true;
    }

    private void indexT9(String pinyin, String word, int rank) {
        String digits = toT9Digits(pinyin);
        if (digits.isEmpty()) return;

        // Earlier words in a lexicon row are treated as more common.
        // Multi-character words/phrases get a modest bonus so normal phrases
        // beat improbable chains of unrelated single characters.
        int score = 1000 - Math.min(rank, 50) * 12 + Math.min(word.length(), 6) * 45;
        T9Token token = new T9Token(pinyin, word, score);

        List<T9Token> list = t9Exact.get(digits);
        if (list == null) {
            list = new ArrayList<>();
            t9Exact.put(digits, list);
        }
        list.add(token);

        int max = Math.min(digits.length(), MAX_TOKEN_DIGITS);
        for (int i = 1; i <= max; i++) {
            String prefix = digits.substring(0, i);
            T9Token old = t9PrefixBest.get(prefix);
            if (old == null || token.score > old.score) {
                t9PrefixBest.put(prefix, token);
            }
        }
    }

    // ---------------- 26-key ----------------

    public List<String> search(String rawPinyin, int limit) {
        String key = normalize(rawPinyin);
        if (key.isEmpty()) return Collections.emptyList();

        String cacheKey = "q#" + key + "#" + limit;
        List<String> cached = searchCache.get(cacheKey);
        if (cached != null) return new ArrayList<>(cached);

        ArrayList<String> out = new ArrayList<>();
        List<String> direct = lexicon.get(key);
        if (direct != null) addAllUnique(out, direct, limit);

        if (out.size() < limit) {
            for (String s : segmentPinyin(key, limit)) {
                addUnique(out, s, limit);
            }
        }

        List<String> saved = Collections.unmodifiableList(new ArrayList<>(out));
        searchCache.put(cacheKey, saved);
        return new ArrayList<>(saved);
    }

    private List<String> segmentPinyin(String key, int limit) {
        Map<Integer, List<String>> memo = new HashMap<>();
        return segmentPinyinFrom(key, 0, Math.max(1, limit), memo);
    }

    private List<String> segmentPinyinFrom(String key, int pos, int limit,
                                           Map<Integer, List<String>> memo) {
        if (pos == key.length()) {
            return new ArrayList<>(Collections.singletonList(""));
        }
        List<String> hit = memo.get(pos);
        if (hit != null) return hit;

        ArrayList<String> out = new ArrayList<>();
        int maxEnd = Math.min(key.length(), pos + 7);
        for (int end = maxEnd; end > pos; end--) {
            List<String> words = lexicon.get(key.substring(pos, end));
            if (words == null || words.isEmpty()) continue;

            List<String> tails = segmentPinyinFrom(key, end, limit, memo);
            if (tails.isEmpty()) continue;

            int wordCap = Math.min(words.size(), 3);
            for (int w = 0; w < wordCap; w++) {
                for (String tail : tails) {
                    addUnique(out, words.get(w) + tail, limit);
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

    // ---------------- 9-key / T9 ----------------

    public List<String> searchT9(String digits, int limit) {
        String d = cleanT9(digits);
        if (d.isEmpty()) return Collections.emptyList();

        String cacheKey = "t#" + d + "#" + limit;
        List<String> cached = searchCache.get(cacheKey);
        if (cached != null) return new ArrayList<>(cached);

        ArrayList<String> out = new ArrayList<>();

        List<T9Token> direct = t9Exact.get(d);
        if (direct != null) {
            List<T9Token> sorted = topTokens(direct, TOKEN_CAP);
            for (T9Token token : sorted) addUnique(out, token.word, limit);
        }

        if (out.size() < limit) {
            for (T9State state : decodeT9(d)) {
                addUnique(out, state.text, limit);
                if (out.size() >= limit) break;
            }
        }

        // While the last syllable is incomplete, keep at least one useful
        // candidate visible instead of freezing or showing raw digits.
        if (out.size() < limit) {
            T9Token prefix = t9PrefixBest.get(d);
            if (prefix != null) addUnique(out, prefix.word, limit);
        }

        List<String> saved = Collections.unmodifiableList(new ArrayList<>(out));
        searchCache.put(cacheKey, saved);
        return new ArrayList<>(saved);
    }

    public String displayT9(String digits) {
        String d = cleanT9(digits);
        if (d.isEmpty()) return "";

        List<T9State> states = decodeT9(d);
        if (!states.isEmpty()) return states.get(0).pinyin;

        T9Token token = t9PrefixBest.get(d);
        return token == null ? "" : token.pinyin;
    }

    public String displayT9Safe(String digits) {
        String d = cleanT9(digits);
        if (d.isEmpty()) return "";

        String exact = displayT9(d);
        if (!exact.isEmpty()) return exact;

        // Do not scan the dictionary. Prefix lookup is O(1).
        for (int cut = d.length() - 1; cut > 0; cut--) {
            T9Token token = t9PrefixBest.get(d.substring(0, cut));
            if (token != null) return token.pinyin;
        }
        return "";
    }

    public boolean hasT9Prefix(String digits) {
        String d = cleanT9(digits);
        return d.isEmpty() || t9PrefixBest.containsKey(d) || !decodeT9(d).isEmpty();
    }

    private List<T9State> decodeT9(String digits) {
        List<T9State> cached = t9Cache.get(digits);
        if (cached != null) return cached;

        final int n = digits.length();
        Map<Integer, List<T9State>> beams = new HashMap<>();
        beams.put(0, new ArrayList<>(Collections.singletonList(new T9State(0, "", ""))));

        for (int pos = 0; pos < n; pos++) {
            List<T9State> current = beams.get(pos);
            if (current == null || current.isEmpty()) continue;
            current = topStates(current, BEAM_WIDTH);

            int maxEnd = Math.min(n, pos + MAX_TOKEN_DIGITS);
            for (int end = pos + 1; end <= maxEnd; end++) {
                List<T9Token> tokens = t9Exact.get(digits.substring(pos, end));
                if (tokens == null || tokens.isEmpty()) continue;

                List<T9Token> bestTokens = topTokens(tokens, TOKEN_CAP);
                List<T9State> target = beams.get(end);
                if (target == null) {
                    target = new ArrayList<>();
                    beams.put(end, target);
                }

                for (T9State state : current) {
                    for (T9Token token : bestTokens) {
                        int score = state.score + token.score - 80;
                        target.add(new T9State(
                                score,
                                state.text + token.word,
                                state.pinyin.isEmpty() ? token.pinyin : state.pinyin + " " + token.pinyin));
                    }
                }

                if (target.size() > BEAM_WIDTH * 3) {
                    beams.put(end, topStates(target, BEAM_WIDTH));
                }
            }
        }

        List<T9State> finals = beams.get(n);
        if (finals == null) finals = Collections.emptyList();
        List<T9State> saved = Collections.unmodifiableList(topStates(new ArrayList<>(finals), BEAM_WIDTH));
        t9Cache.put(digits, saved);
        return saved;
    }

    private List<T9Token> topTokens(List<T9Token> input, int count) {
        ArrayList<T9Token> copy = new ArrayList<>(input);
        Collections.sort(copy, (a, b) -> Integer.compare(b.score, a.score));
        if (copy.size() > count) return new ArrayList<>(copy.subList(0, count));
        return copy;
    }

    private List<T9State> topStates(List<T9State> input, int count) {
        Collections.sort(input, (a, b) -> Integer.compare(b.score, a.score));
        ArrayList<T9State> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (T9State state : input) {
            if (!seen.add(state.text)) continue;
            out.add(state);
            if (out.size() >= count) break;
        }
        return out;
    }

    // ---------------- helpers ----------------

    public String formatForDisplay(String rawPinyin) {
        if (rawPinyin == null) return "";
        return rawPinyin.toLowerCase()
                .replace("ü", "v")
                .replaceAll("\\s+", "")
                .replace("'", " ")
                .trim();
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase()
                .replace("ü", "v")
                .replace(" ", "")
                .replace("'", "")
                .trim();
    }

    private String cleanT9(String digits) {
        if (digits == null) return "";
        return digits.replaceAll("[^2-9]", "");
    }

    private String toT9Digits(String pinyin) {
        String value = normalize(pinyin);
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
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

    private void addAllUnique(List<String> out, List<String> values, int limit) {
        for (String value : values) {
            addUnique(out, value, limit);
            if (out.size() >= limit) return;
        }
    }

    private void addUnique(List<String> out, String value, int limit) {
        if (value == null || value.isEmpty() || out.size() >= limit || out.contains(value)) return;
        out.add(value);
    }
}
