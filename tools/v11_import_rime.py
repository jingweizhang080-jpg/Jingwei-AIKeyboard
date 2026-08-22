from pathlib import Path
from collections import defaultdict
import sys

ASSET = Path('app/src/main/assets/pinyin_lexicon.tsv')
RIME = Path('/tmp/pinyin_simp.dict.yaml')

# Preserve our hand-tuned starter/chat entries first.
base = defaultdict(list)
order = []
for raw in ASSET.read_text(encoding='utf-8').splitlines():
    line = raw.strip()
    if not line or line.startswith('#') or '\t' not in line:
        continue
    py, words = line.split('\t', 1)
    py = py.strip().lower().replace(' ', '').replace("'", '')
    if not py:
        continue
    if py not in base:
        order.append(py)
    for w in words.split('|'):
        w = w.strip()
        if w and w not in base[py]:
            base[py].append(w)

# Import Rime pinyin-simp. Format: word<TAB>pinyin with spaces<TAB>weight.
# We keep the highest-frequency candidates for each pinyin key; this gives the
# temporary Java decoder a real, frequency-ranked vocabulary instead of a tiny demo list.
rime = defaultdict(list)
started = False
for raw in RIME.read_text(encoding='utf-8').splitlines():
    line = raw.strip()
    if not started:
        if line == '...':
            started = True
        continue
    if not line or line.startswith('#'):
        continue
    parts = line.split('\t')
    if len(parts) < 2:
        continue
    word = parts[0].strip()
    py = parts[1].strip().lower().replace(' ', '').replace("'", '')
    if not word or not py or len(py) > 80 or len(word) > 12:
        continue
    try:
        weight = int(parts[2]) if len(parts) > 2 and parts[2].strip().isdigit() else 1
    except Exception:
        weight = 1
    rime[py].append((weight, word))

# Cap each exact pinyin bucket so startup and T9 indexing stay bounded.
for py, items in rime.items():
    items.sort(key=lambda x: (-x[0], len(x[1]), x[1]))
    if py not in base:
        order.append(py)
    for _, word in items[:16]:
        if word not in base[py]:
            base[py].append(word)

out = [
    '# Jingwei AI Keyboard merged lexicon',
    '# Base chat phrases + Rime pinyin-simp (frequency ranked)',
    '# Upstream: https://github.com/rime/rime-pinyin-simp',
]
for py in order:
    words = base[py][:20]
    if words:
        out.append(py + '\t' + '|'.join(words))
ASSET.write_text('\n'.join(out) + '\n', encoding='utf-8')
print(f'Wrote {len(order)} pinyin keys to {ASSET}')
