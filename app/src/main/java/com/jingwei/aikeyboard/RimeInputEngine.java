package com.jingwei.aikeyboard;

import android.content.Context;

import java.util.Collections;
import java.util.List;

/**
 * Stateful facade for the production Rime path.
 *
 * Rime owns the only production input state. Java reads the session input and
 * composition after every operation instead of maintaining a second buffer.
 */
public final class RimeInputEngine {
    private final RimeRuntime runtime;
    private boolean nineKey;
    private boolean schemaSelected;

    public RimeInputEngine(Context context) {
        runtime = new RimeRuntime(context);
    }

    public synchronized boolean start() {
        boolean started = runtime.start();
        schemaSelected = started;
        if (started) nineKey = false;
        return started;
    }

    public boolean isReady() {
        return runtime.isReady() && schemaSelected;
    }

    /**
     * Switch schemas atomically from Java's point of view. The mode flag is
     * updated only after Rime confirms the switch, so a failed schema change
     * can never make Java send T9 digits into the 26-key schema (or vice versa).
     */
    public synchronized boolean setNineKey(boolean enabled) {
        if (!runtime.isReady()) return false;
        if (schemaSelected && nineKey == enabled) return true;

        boolean switched = runtime.useNineKey(enabled);
        if (switched) {
            nineKey = enabled;
            schemaSelected = true;
        }
        return switched;
    }

    public synchronized void reset() {
        if (isReady()) RimeBridge.clearComposition();
    }

    public synchronized boolean append(char value) {
        if (!isReady()) return false;
        if (nineKey) {
            // 2-9 are T9 input. Apostrophe is also accepted as an explicit
            // syllable boundary so the “分词” key works in the native path.
            if (!((value >= '2' && value <= '9') || value == '\'')) return false;
        } else {
            if (!((value >= 'a' && value <= 'z') || value == '\'')) return false;
        }
        return RimeBridge.processAscii(String.valueOf(value));
    }

    /**
     * Backspace is processed by Rime itself. The caller then reads input and
     * composition from that same session, so rapid delete cannot expose stale
     * Java-side pinyin.
     */
    public synchronized boolean backspace() {
        return isReady() && !rawInput().isEmpty() && RimeBridge.processBackspace();
    }

    public synchronized String rawInput() {
        return isReady() ? RimeBridge.getInput() : "";
    }

    public synchronized String composition() {
        if (!isReady()) return "";
        String value = RimeBridge.getComposition();
        return value == null || value.isEmpty() ? rawInput() : value;
    }

    public synchronized List<String> candidates(int limit) {
        if (!isReady()) return Collections.emptyList();
        return RimeBridge.getCandidates(Math.max(1, Math.min(limit, 30)));
    }

    public synchronized String select(int index) {
        if (!isReady()) return "";
        String committed = RimeBridge.selectCandidate(index);
        return committed == null ? "" : committed;
    }

    public synchronized void stop() {
        schemaSelected = false;
        nineKey = false;
        runtime.stop();
    }
}
