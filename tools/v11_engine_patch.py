from pathlib import Path

ENGINE = Path('app/src/main/java/com/jingwei/aikeyboard/PinyinEngine.java')
p = ENGINE.read_text(encoding='utf-8')

p = p.replace(
    '    private final Map<String, String> t9Syllable = new HashMap<>();\n',
    '    private final Map<String, String> t9Syllable = new HashMap<>();\n'
    '    private final Map<String, Integer> t9SyllableScore = new HashMap<>();\n'
)
p = p.replace(
    '        t9Syllable.clear();\n        searchCache.clear();',
    '        t9Syllable.clear();\n        t9SyllableScore.clear();\n        searchCache.clear();'
)

old_parse = '''                String[] rawWords = line.substring(tab + 1).split("\\\\|");
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
                    boolean hasSingleChar = false;
                    for (String w : words) {
                        if (w.length() == 1) { hasSingleChar = true; break; }
                    }
                    if (hasSingleChar && py.length() <= 6) {
                        String digits = toT9Digits(py);
                        if (!digits.isEmpty() && !t9Syllable.containsKey(digits)) {
                            t9Syllable.put(digits, py);
                        }
                    }
                }'''

new_parse = '''                int tab2 = line.indexOf('\\t', tab + 1);
                String wordsPart = tab2 < 0 ? line.substring(tab + 1) : line.substring(tab + 1, tab2);
                String weightsPart = tab2 < 0 ? "" : line.substring(tab2 + 1);
                String[] rawWords = wordsPart.split("\\\\|");
                String[] rawWeights = weightsPart.isEmpty() ? new String[0] : weightsPart.split("\\\\|");
                ArrayList<String> words = new ArrayList<>();
                int rank = 0;
                int bestSingleWeight = 0;
                for (int i = 0; i < rawWords.length; i++) {
                    String word = rawWords[i].trim();
                    if (word.isEmpty() || words.contains(word)) continue;
                    int frequency = 1;
                    if (i < rawWeights.length) {
                        try { frequency = Math.max(1, Integer.parseInt(rawWeights[i].trim())); }
                        catch (Throwable ignored) {}
                    }
                    words.add(word);
                    indexT9(py, word, rank++, frequency);
                    if (word.length() == 1) bestSingleWeight = Math.max(bestSingleWeight, frequency);
                }
                if (!words.isEmpty()) {
                    lexicon.put(py, Collections.unmodifiableList(words));
                    if (bestSingleWeight > 0 && py.length() <= 6) {
                        String digits = toT9Digits(py);
                        Integer oldScore = t9SyllableScore.get(digits);
                        if (!digits.isEmpty() && (oldScore == null || bestSingleWeight > oldScore)) {
                            t9Syllable.put(digits, py);
                            t9SyllableScore.put(digits, bestSingleWeight);
                        }
                    }
                }'''

if old_parse not in p:
    raise RuntimeError('parser block not found')
p = p.replace(old_parse, new_parse)

old_sig = '    private void indexT9(String pinyin, String word, int rank) {'
new_sig = '    private void indexT9(String pinyin, String word, int rank, int frequency) {'
if old_sig not in p:
    raise RuntimeError('indexT9 signature not found')
p = p.replace(old_sig, new_sig)

old_score = '        int score = Math.min(word.length(), 8) * 220 - Math.min(rank, 50) * 8;'
new_score = '''        int freqBonus = (int) Math.min(1100, Math.log10(Math.max(1, frequency) + 1.0) * 260.0);
        int score = Math.min(word.length(), 8) * 300 + freqBonus - Math.min(rank, 50) * 6;
        if (word.length() == 1) score -= 180;'''
if old_score not in p:
    raise RuntimeError('score line not found')
p = p.replace(old_score, new_score)

# Wider phrase coverage, but keep bounded enough for IME latency.
p = p.replace('    private static final int BEAM_WIDTH = 10;', '    private static final int BEAM_WIDTH = 14;')
p = p.replace('    private static final int TOKEN_CAP = 5;', '    private static final int TOKEN_CAP = 8;')
p = p.replace('    private static final int MAX_TOKEN_DIGITS = 12;', '    private static final int MAX_TOKEN_DIGITS = 24;')

ENGINE.write_text(p, encoding='utf-8')
print('V0.11 weighted T9 engine patch applied')
