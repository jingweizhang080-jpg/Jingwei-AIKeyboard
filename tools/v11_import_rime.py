from pathlib import Path
from collections import defaultdict

ASSET = Path('app/src/main/assets/pinyin_lexicon.tsv')
RIME = Path('/tmp/pinyin_simp.dict.yaml')

# Preserve existing hand-tuned entries. Old rows may not have weights.
base = defaultdict(list)   # py -> [(weight, word)]
order = []
for raw in ASSET.read_text(encoding='utf-8').splitlines():
    line = raw.strip()
    if not line or line.startswith('#') or '\t' not in line:
        continue
    parts = line.split('\t')
    py = parts[0].strip().lower().replace(' ', '').replace("'", '')
    if not py:
        continue
    if py not in base:
        order.append(py)
    words = parts[1].split('|') if len(parts) > 1 else []
    weights = parts[2].split('|') if len(parts) > 2 else []
    seen = {w for _, w in base[py]}
    for i, raw_word in enumerate(words):
        word = raw_word.strip()
        if not word or word in seen:
            continue
        try:
            weight = int(weights[i]) if i < len(weights) else 500000
        except Exception:
            weight = 500000
        # Give our curated chat phrases a strong but not absolute prior.
        base[py].append((max(1, weight), word))
        seen.add(word)

# Import Rime pinyin-simp. Format: word<TAB>pinyin with spaces<TAB>weight.
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
    if not word or not py or len(py) > 96 or len(word) > 16:
        continue
    try:
        weight = int(parts[2]) if len(parts) > 2 and parts[2].strip().isdigit() else 1
    except Exception:
        weight = 1
    rime[py].append((max(1, weight), word))

for py, items in rime.items():
    items.sort(key=lambda x: (-x[0], len(x[1]), x[1]))
    if py not in base:
        order.append(py)
    existing = {w for _, w in base[py]}
    for weight, word in items[:24]:
        if word not in existing:
            base[py].append((weight, word))
            existing.add(word)

out = [
    '# Jingwei AI Keyboard merged lexicon',
    '# Format: pinyin<TAB>candidate1|candidate2...<TAB>weight1|weight2...',
    '# Base chat phrases + Rime pinyin-simp, frequency preserved',
    '# Upstream: https://github.com/rime/rime-pinyin-simp',
]
for py in order:
    items = base[py]
    # Global frequency matters for T9 collisions, so sort every bucket by weight.
    items.sort(key=lambda x: (-x[0], len(x[1]), x[1]))
    items = items[:28]
    if not items:
        continue
    out.append(py + '\t' + '|'.join(w for _, w in items) + '\t' + '|'.join(str(weight) for weight, _ in items))

ASSET.write_text('\n'.join(out) + '\n', encoding='utf-8')
print(f'Wrote {len(order)} weighted pinyin keys to {ASSET}')
