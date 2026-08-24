#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
service = ROOT / "app/src/main/java/com/jingwei/aikeyboard/JingweiImeService.java"
text = service.read_text(encoding="utf-8")

old = '''        String display;
        if (snapshotNineKey) {
            display = pinyinEngine == null ? "" : pinyinEngine.quickDisplayT9(snapshot);
        } else {
            display = pinyinEngine == null
                    ? snapshot.replace("'", " ")
                    : pinyinEngine.formatForDisplay(snapshot);
        }
'''
new = '''        String display;
        if (rimeInputEngine != null && rimeInputEngine.isReady()) {
            synchronized (rimeInputEngine) {
                rimeInputEngine.setNineKey(snapshotNineKey);
                for (int i = 0; i < snapshot.length(); i++) rimeInputEngine.append(snapshot.charAt(i));
                display = rimeInputEngine.composition();
            }
        } else if (snapshotNineKey) {
            display = pinyinEngine == null ? "" : pinyinEngine.quickDisplayT9(snapshot);
        } else {
            display = pinyinEngine == null
                    ? snapshot.replace("'", " ")
                    : pinyinEngine.formatForDisplay(snapshot);
        }
'''

if new in text:
    print("native composition display already wired")
elif old in text:
    service.write_text(text.replace(old, new, 1), encoding="utf-8")
    print("native composition display wired")
else:
    raise SystemExit("display block not found; refusing unsafe patch")
