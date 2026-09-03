package com.jingwei.aikeyboard;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Copies bundled Rime assets to writable app storage and owns the native engine lifecycle.
 * The old Java PinyinEngine remains available as a fallback until V0.12 is fully verified.
 */
public final class RimeRuntime {
    private static final String ASSET_VERSION = "0.12.2";

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

            deployAssetsIfNeeded(base);

            ready = RimeBridge.start(
                    sharedDir.getAbsolutePath(),
                    userDir.getAbsolutePath(),
                    ASSET_VERSION);
            if (!ready) return false;

            // A session without the expected schema is not safe to expose as ready.
            if (!RimeBridge.setSchema("jingwei_pinyin")) {
                RimeBridge.stop();
                ready = false;
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            if (RimeBridge.isNativeAvailable()) RimeBridge.stop();
            ready = false;
            return false;
        }
    }

    public synchronized void stop() {
        if (RimeBridge.isNativeAvailable()) RimeBridge.stop();
        ready = false;
    }

    public synchronized boolean useNineKey(boolean enabled) {
        if (!isReady()) return false;
        RimeBridge.clearComposition();
        return RimeBridge.setSchema(enabled ? "jingwei_t9" : "jingwei_pinyin");
    }

    /**
     * Asset files are immutable for a given app-side Rime version. Avoid
     * overwriting them every time the IME view is recreated; otherwise their
     * mtimes change and Rime may perform unnecessary maintenance work.
     */
    private void deployAssetsIfNeeded(File base) throws Exception {
        File marker = new File(base, "assets.version");
        if (assetsCurrent(marker)) return;

        copyAsset("rime/default.yaml");
        copyAsset("rime/jingwei_pinyin.schema.yaml");
        copyAsset("rime/jingwei_t9.schema.yaml");
        copyAsset("rime/jingwei.dict.yaml");

        try (FileOutputStream out = new FileOutputStream(marker, false)) {
            out.write(ASSET_VERSION.getBytes(StandardCharsets.UTF_8));
        }
    }

    private boolean assetsCurrent(File marker) {
        if (!marker.isFile()) return false;
        if (!new File(sharedDir, "default.yaml").isFile()
                || !new File(sharedDir, "jingwei_pinyin.schema.yaml").isFile()
                || !new File(sharedDir, "jingwei_t9.schema.yaml").isFile()
                || !new File(sharedDir, "jingwei.dict.yaml").isFile()) {
            return false;
        }

        try (FileInputStream in = new FileInputStream(marker)) {
            byte[] buffer = new byte[64];
            int count = in.read(buffer);
            if (count <= 0) return false;
            String value = new String(buffer, 0, count, StandardCharsets.UTF_8).trim();
            return ASSET_VERSION.equals(value);
        } catch (Throwable ignored) {
            return false;
        }
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
