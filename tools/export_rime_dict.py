#!/usr/bin/env python3
"""Convert app/src/main/assets/pinyin_lexicon.tsv into a Rime dictionary.

Input rows:
  pinyin<TAB>word1|word2|...<TAB>weight1|weight2|...

Output rows:
  word<TAB>pinyin<TAB>weight
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/assets/pinyin_lexicon.tsv"
OUT = ROOT / "app/src/main/assets/rime/jingwei.dict.yaml"

header = """# Rime dictionary\n---\nname: jingwei\nversion: \"0.12\"\nsort: by_weight\nuse_preset_vocabulary: true\n...\n"""

rows = []
for raw in SRC.read_text(encoding="utf-8").splitlines():
    line = raw.strip()
    if not line or line.startswith("#"):
        continue
    parts = line.split("\t")
    if len(parts) < 2:
        continue
    py = parts[0].strip().replace("'", " ")
    words = [w.strip() for w in parts[1].split("|") if w.strip()]
    weights = parts[2].split("|") if len(parts) > 2 else []
    for i, word in enumerate(words):
        try:
            weight = max(1, int(weights[i].strip())) if i < len(weights) else max(1, 100000 - i * 100)
        except ValueError:
            weight = max(1, 100000 - i * 100)
        rows.append((word, py, weight))

# Deduplicate word+pinyin, keeping the largest weight.
best = {}
for word, py, weight in rows:
    key = (word, py)
    if weight > best.get(key, 0):
        best[key] = weight

ordered = sorted(((w, p, wt) for (w, p), wt in best.items()), key=lambda x: (-x[2], x[1], x[0]))
OUT.parent.mkdir(parents=True, exist_ok=True)
with OUT.open("w", encoding="utf-8", newline="\n") as f:
    f.write(header)
    for word, py, weight in ordered:
        f.write(f"{word}\t{py}\t{weight}\n")

print(f"wrote {len(ordered)} Rime dictionary entries to {OUT}")
