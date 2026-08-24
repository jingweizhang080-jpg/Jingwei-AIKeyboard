package com.jingwei.aikeyboard;

import android.content.Context;

import java.util.Collections;
import java.util.List;

/**
 * Stateful facade for the production Rime path.
 *
 * Important invariant: the Java-side buffer and Rime composition are always
 * rebuilt together. Backspace therefore cannot leave stale/mixed pinyin in
 * the native session, which was one of the largest V0.11 UX problems.
 */
public final class RimeInputEngine {
    private final RimeRuntime runtime;
    private final StringBuilder raw = new StringBuilder();
    private boolean nineKey;

    public RimeInputEngine(Context context) {
        runtime = new RimeRuntime(context);
    }

    public boolean start() {
        return runtime.start();
    }

    public boolean isReady() {
        return runtime.isReady();
    }

    public synchronized boolean setNineKey(boolean enabled) {
        nineKey = enabled;
        raw.setLength(0);
        return runtime.useNineKey(enabled);
    }

    public synchronized void reset() {
        raw.setLength(0);
        RimeBridge.clearComposition();
    }

    public synchronized boolean append(char value) {
        if (!isReady()) return false;
        if (nineKey) {
            if (value < '2' || value > '9') return false;
        } else {
            if (!((value >= 'a' && value <= 'z') || value == '\'')) return false;
        }
        raw.append(value);
        return RimeBridge.processAscii(String.valueOf(value));
    }

    /**
     * Rebuild instead of trusting two independent deletion states. This is a
     * little more work than sending BackSpace, but composition strings are tiny
     * and it guarantees that rapid delete can never expose stale Rime pinyin.
     */
    public synchronized boolean backspace() {
        if (!isReady() || raw.length() == 0) return false;
        raw.deleteCharAt(raw.length() - 1);
        replayLocked();
        return true;
    }

    public synchronized String rawInput() {
        return raw.toString();
    }

    public synchronized String composition() {
        if (!isReady()) return raw.toString();
        String value = RimeBridge.getComposition();
        return value == null || value.isEmpty() ? raw.toString() : value;
    }

    public synchronized List<String> candidates(int limit) {
        if (!isReady()) return Collections.emptyList();
        return RimeBridge.getCandidates(Math.max(1, limit));
    }

    public synchronized String select(int index) {
        if (!isReady()) return "";
        String committed = RimeBridge.selectCandidate(index);
        if (committed != null && !committed.isEmpty()) {
            raw.setLength(0);
        }
        return committed == null ? "" : committed;
    }

    public synchronized void stop() {
        raw.setLength(0);
        runtime.stop();
    }

    private void replayLocked() {
        RimeBridge.clearComposition();
        if (raw.length() > 0) RimeBridge.processAscii(raw.toString());
    }
}
