package com.jingwei.aikeyboard;

import java.util.Collections;
import java.util.List;

/**
 * V0.12 native Rime bridge boundary.
 *
 * This class deliberately loads librime lazily and fails closed so the app can
 * still build and fall back to the existing Java engine while the native .so
 * and JNI implementation are being wired in.
 */
public final class RimeBridge {
    private static final boolean NATIVE_AVAILABLE;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("rime_jni");
            loaded = true;
        } catch (Throwable ignored) {
            loaded = false;
        }
        NATIVE_AVAILABLE = loaded;
    }

    private RimeBridge() {}

    public static boolean isNativeAvailable() {
        return NATIVE_AVAILABLE;
    }

    public static boolean start(String sharedDir, String userDir, String versionName) {
        if (!NATIVE_AVAILABLE) return false;
        try {
            return nativeStart(sharedDir, userDir, versionName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void stop() {
        if (!NATIVE_AVAILABLE) return;
        try {
            nativeStop();
        } catch (Throwable ignored) {}
    }

    public static boolean clearComposition() {
        if (!NATIVE_AVAILABLE) return false;
        try {
            nativeClearComposition();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean processAscii(String text) {
        if (!NATIVE_AVAILABLE || text == null || text.isEmpty()) return false;
        try {
            return nativeProcessAscii(text);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String getComposition() {
        if (!NATIVE_AVAILABLE) return "";
        try {
            String value = nativeGetComposition();
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static List<String> getCandidates(int limit) {
        if (!NATIVE_AVAILABLE) return Collections.emptyList();
        try {
            String[] values = nativeGetCandidates(Math.max(1, limit));
            if (values == null || values.length == 0) return Collections.emptyList();
            return java.util.Arrays.asList(values);
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    public static String selectCandidate(int index) {
        if (!NATIVE_AVAILABLE) return "";
        try {
            String value = nativeSelectCandidate(index);
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static native boolean nativeStart(String sharedDir, String userDir, String versionName);
    private static native void nativeStop();
    private static native void nativeClearComposition();
    private static native boolean nativeProcessAscii(String text);
    private static native String nativeGetComposition();
    private static native String[] nativeGetCandidates(int limit);
    private static native String nativeSelectCandidate(int index);
}
