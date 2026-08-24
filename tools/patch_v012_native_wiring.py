#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

runtime = ROOT / "app/src/main/java/com/jingwei/aikeyboard/RimeRuntime.java"
cpp = ROOT / "app/src/main/cpp/rime_jni.cpp"
service = ROOT / "app/src/main/java/com/jingwei/aikeyboard/JingweiImeService.java"


def replace_once(path: Path, old: str, new: str):
    text = path.read_text(encoding="utf-8")
    if new in text:
        return False
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    return True

# Keep Java/native symbol names aligned.
replace_once(
    cpp,
    "Java_com_jingwei_aikeyboard_RimeBridge_nativeSelectSchema",
    "Java_com_jingwei_aikeyboard_RimeBridge_nativeSetSchema",
)

# Runtime API was renamed from selectSchema -> setSchema.
text = runtime.read_text(encoding="utf-8")
text = text.replace("RimeBridge.selectSchema(\"jingwei_pinyin\")", "RimeBridge.setSchema(\"jingwei_pinyin\")")
text = text.replace("RimeBridge.selectSchema(enabled ? \"jingwei_t9\" : \"jingwei_pinyin\")",
                    "RimeBridge.setSchema(enabled ? \"jingwei_t9\" : \"jingwei_pinyin\")")
runtime.write_text(text, encoding="utf-8")

# Wire the native facade alongside the legacy fallback.
replace_once(
    service,
    "    private PinyinEngine pinyinEngine;\n",
    "    private PinyinEngine pinyinEngine;\n    private RimeInputEngine rimeInputEngine;\n",
)

replace_once(
    service,
    "        pinyinEngine = new PinyinEngine(this);\n",
    "        rimeInputEngine = new RimeInputEngine(this);\n"
    "        executor.execute(() -> {\n"
    "            boolean nativeReady = false;\n"
    "            try { nativeReady = rimeInputEngine.start(); } catch (Throwable ignored) {}\n"
    "            final boolean ready = nativeReady;\n"
    "            main.post(() -> {\n"
    "                if (pinyinStatus != null && ready) pinyinStatus.setText(\"Rime\");\n"
    "            });\n"
    "        });\n\n"
    "        pinyinEngine = new PinyinEngine(this);\n",
)

# Native candidate path first; legacy engine remains fallback until on-device validation completes.
old_words = """            List<String> words = pinyinEngine == null
                    ? new ArrayList<>()
                    : (snapshotNineKey ? pinyinEngine.searchT9(snapshot, 10)
                                       : pinyinEngine.search(snapshot, 10));"""
new_words = """            List<String> words;
            if (rimeInputEngine != null && rimeInputEngine.isReady()) {
                synchronized (rimeInputEngine) {
                    rimeInputEngine.setNineKey(snapshotNineKey);
                    for (int i = 0; i < snapshot.length(); i++) rimeInputEngine.append(snapshot.charAt(i));
                    words = new ArrayList<>(rimeInputEngine.candidates(20));
                }
            } else {
                words = pinyinEngine == null
                        ? new ArrayList<>()
                        : (snapshotNineKey ? pinyinEngine.searchT9(snapshot, 20)
                                           : pinyinEngine.search(snapshot, 20));
            }"""
replace_once(service, old_words, new_words)

# On deletion, clear the native composition immediately before the latest snapshot is replayed.
old_delete = """            // V0.10: let the latest-only pipeline refresh composing/candidates.
            showPinyinCandidates();"""
new_delete = """            // V0.12: invalidate native composition immediately. The next render replays
            // exactly the surviving buffer, preventing mixed/stale pinyin after rapid delete.
            if (rimeInputEngine != null && rimeInputEngine.isReady()) rimeInputEngine.reset();
            showPinyinCandidates();"""
replace_once(service, old_delete, new_delete)

print("V0.12 native wiring patch applied")
