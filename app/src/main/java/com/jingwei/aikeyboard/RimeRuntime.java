package com.jingwei.aikeyboard;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Copies bundled Rime assets to writable app storage and owns the native engine lifecycle.
 * The old Java PinyinEngine remains available as a fallback until V0.12 is fully verified.
 */
public final class RimeRuntime {
    private final Context context;
    private File sharedDir;
    private File userDir;
    private volatile boolean ready;

    public RimeRuntime(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isReady() {
        return ready && RimeBridge.isNativeAvailable();
    }

    public synchronized boolean start() {
        if (isReady()) return true;
        if (!RimeBridge.isNativeAvailable()) return false;
        try {
            File base = new File(context.getFilesDir(), "rime");
            sharedDir = new File(base, "shared");
            userDir = new File(base, "user");
            if (!sharedDir.exists() && !sharedDir.mkdirs()) return false;
            if (!userDir.exists() && !userDir.mkdirs()) return false;

            copyAsset("rime/default.yaml");
            copyAsset("rime/jingwei_pinyin.schema.yaml");
            copyAsset("rime/jingwei_t9.schema.yaml");
            copyAsset("rime/jingwei.dict.yaml");

            ready = RimeBridge.start(
                    sharedDir.getAbsolutePath(),
                    userDir.getAbsolutePath(),
                    "0.12.0");
            if (ready) RimeBridge.setSchema("jingwei_pinyin");
            return ready;
        } catch (Throwable ignored) {
            ready = false;
            return false;
        }
    }

    public synchronized void stop() {
        if (RimeBridge.isNativeAvailable()) RimeBridge.stop();
        ready = false;
    }

    public boolean useNineKey(boolean enabled) {
        if (!isReady()) return false;
        RimeBridge.clearComposition();
        return RimeBridge.setSchema(enabled ? "jingwei_t9" : "jingwei_pinyin");
    }

    private void copyAsset(String assetPath) throws Exception {
        String name = assetPath.substring(assetPath.lastIndexOf('/') + 1);
        File target = new File(sharedDir, name);
        try (InputStream in = context.getAssets().open(assetPath);
             FileOutputStream out = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (n > 0) out.write(buffer, 0, n);
            }
        }
    }
}
