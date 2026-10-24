package com.dsh.subtitlestitch;

import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

/**
 * Quick-settings tile: one tap starts/stops the screenshot watcher.
 *
 * Requires API 24+. The tile is added by the user in the control centre
 * (编辑 → 添加图块). We never call requestAddTileService automatically so the
 * user stays in control of their tiles.
 */
public class WatchTileService extends TileService {

    private static final String TAG = "SubtitleStitch";

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile(watcherRunning());
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean running = watcherRunning();
        Log.i(TAG, "tile clicked, running=" + running);
        if (running) {
            ShotWatcherService.stop(this);
            // stop is async; reflect the intent immediately
            updateTile(false);
        } else {
            if (!MediaStoreUtil.canReadImages(this)) {
                // send the user to the app so they can grant it
                Intent i = new Intent(this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivityAndCollapse(
                        android.app.PendingIntent.getActivity(this, 0, i,
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                        | (Build.VERSION.SDK_INT >= 23
                                        ? android.app.PendingIntent.FLAG_IMMUTABLE : 0)));
                return;
            }
            ShotWatcherService.start(this);
            updateTile(true);
        }
    }

    /**
     * Is the watcher actually alive? Prefer the live system answer over a static
     * flag, which a process restart would clear.
     */
    private boolean watcherRunning() {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                for (android.app.ActivityManager.RunningServiceInfo info
                        : am.getRunningServices(Integer.MAX_VALUE)) {
                    if (info.service != null && ShotWatcherService.class.getName()
                            .equals(info.service.getClassName())) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) { }
        return ShotWatcherService.isRunning;
    }

    private void updateTile(boolean active) {
        if (Build.VERSION.SDK_INT < 24) {
            return;
        }
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        try {
            tile.setLabel(active ? "截图拼接 · 监听中" : "截图拼接 · 已停止");
            tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_tile));
            tile.updateTile();
        } catch (Exception e) {
            Log.w(TAG, "updateTile failed", e);
        }
    }

    /** Ask the system to offer the tile (best-effort, API 33+). */
    public static void requestAddTile(android.content.Context ctx) {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        try {
            android.app.StatusBarManager sbm =
                    (android.app.StatusBarManager) ctx.getSystemService(STATUS_BAR_SERVICE);
            if (sbm == null) {
                return;
            }
            java.lang.reflect.Method m = android.app.StatusBarManager.class.getMethod(
                    "requestAddTileService",
                    android.content.ComponentName.class,
                    CharSequence.class,
                    Icon.class,
                    java.util.concurrent.Executor.class,
                    java.util.function.IntConsumer.class);
            java.util.concurrent.Executor main =
                    command -> new android.os.Handler(android.os.Looper.getMainLooper()).post(command);
            m.invoke(sbm,
                    new android.content.ComponentName(ctx, WatchTileService.class),
                    "字幕拼接",
                    Icon.createWithResource(ctx, R.drawable.ic_tile),
                    main,
                    (java.util.function.IntConsumer) result -> { });
        } catch (Throwable ignored) {
            // API shape differs across OEM builds; the user can still add the
            // tile manually from the control centre.
        }
    }
}
