package com.equentr.subtitlestitch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Watches MediaStore for newly created screenshots.
 *
 * Why ContentObserver: on modern Android a third-party app cannot receive the
 * privileged screenshot broadcasts (those are OEM/system-only). Observing the
 * shared image collection is the supported, non-root way to learn that a new
 * screenshot appeared. We react to onchange on MediaStore.Images and compare the
 * newest row id against the last one we saw.
 *
 * The service holds the captured shots in memory (bounded), exposes them to the
 * UI through a static list, and asks BubbleService to show a floating badge.
 */
public class ShotWatcherService extends Service {

    private static final String TAG = "SubtitleStitch";
    public static final String CHANNEL_ID = "shot_watcher";
    public static final int NOTIFICATION_ID = 1001;
    public static final String ACTION_START = "com.equentr.subtitlestitch.START_WATCH";
    public static final String ACTION_STOP = "com.equentr.subtitlestitch.STOP_WATCH";
    public static final String ACTION_CLEAR = "com.equentr.subtitlestitch.CLEAR_SHOTS";
    /** Notification action: bring the floating bubble back. */
    public static final String ACTION_SHOW_BUBBLE = "com.equentr.subtitlestitch.SHOW_BUBBLE";
    /** Notification action: hide the floating bubble. */
    public static final String ACTION_HIDE_BUBBLE = "com.equentr.subtitlestitch.HIDE_BUBBLE";
    /** Notification action: open the stitch editor directly. */
    public static final String ACTION_OPEN_STITCH = "com.equentr.subtitlestitch.OPEN_STITCH";

    /** Shots captured since the watcher started. Mirrored to ShotStore. */
    private static final List<MediaStoreUtil.Shot> sCaptured =
            java.util.Collections.synchronizedList(new ArrayList<MediaStoreUtil.Shot>());

    private HandlerThread thread;
    private Handler bg;
    private ContentObserver observer;
    private long lastSeenId = -1L;
    private long startTimeMillis = 0L;
    private boolean restored = false;

    public static List<MediaStoreUtil.Shot> captured() {
        synchronized (sCaptured) {
            return new ArrayList<>(sCaptured);
        }
    }

    public static int capturedCount() {
        return sCaptured.size();
    }

    public static void clearCaptured() {
        sCaptured.clear();
    }

    /** Replace the in-memory list (used when restoring from ShotStore). */
    public static void setCaptured(List<MediaStoreUtil.Shot> shots) {
        if (shots == null) {
            return;
        }
        sCaptured.clear();
        sCaptured.addAll(shots);
    }

    public static boolean isRunning = false;

    /**
     * True while we intend to keep the mediaProjection foreground type. The
     * system drops back to the manifest default on every re-promotion, so this
     * must be re-applied each time rather than set once.
     */
    private static boolean sWantProjectionType = false;

    /** Rehydrate the in-memory list from disk (call before reading captured()). */
    private void restoreIfNeeded() {
        if (restored) {
            return;
        }
        restored = true;
        if (sCaptured.isEmpty()) {
            sCaptured.addAll(ShotStore.load(this));
        }
    }

    /** Persist the current list. */
    private void persist() {
        ShotStore.save(this, captured());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("shot-watch");
        thread.start();
        bg = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        isRunning = true;
        // Re-assert foreground types on every entry. The system re-delivers
        // intents whenever the service is re-promoted and resets the types to
        // the manifest default, which silently kills a live projection.
        if (ScreenCapture.hasConsent(this)) {
            sWantProjectionType = true;
        }
        ShotStore.setWatcherRunning(this, true);
        restoreIfNeeded();
        enterForeground(sWantProjectionType);

        if (ACTION_STOP.equals(action)) {
            // Do not leave a stale STOP intent behind: if the user starts the
            // watcher again immediately (e.g. while granting capture consent),
            // the service must come back up rather than honour the old stop.
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_CLEAR.equals(action)) {
            clearCaptured();
            persist();
            updateNotification();
            BubbleService.hideBadge(this);
            return START_STICKY;
        }
        if (ACTION_SHOW_BUBBLE.equals(action)) {
            BubbleService.showAgain(this);
            return START_STICKY;
        }
        if (ACTION_HIDE_BUBBLE.equals(action)) {
            BubbleService.hidePermanently(this);
            return START_STICKY;
        }
        if (action != null && action.startsWith(ACTION_SHOW_BUBBLE + ".refresh")) {
            updateNotification();
            return START_STICKY;
        }
        if (action != null && action.startsWith(ACTION_START + ".prepare")) {
            enterForeground(true);   // promote BEFORE consent is consumed
            return START_STICKY;
        }
        if (action != null && action.startsWith(ACTION_START + ".consent")) {
            enterForeground(true);
            return START_STICKY;
        }
        if (action != null && action.startsWith(ACTION_CLEAR + ".refresh")) {
            updateNotification();
            BubbleService.showAgain(this);
            return START_STICKY;
        }

        if (observer == null) {
            startTimeMillis = System.currentTimeMillis();
            lastSeenId = MediaStoreUtil.newestImageId(this);
            registerObserver();
        }
        return START_STICKY;
    }

    /**
     * Promote to foreground with the correct service types.
     *
     * CRITICAL: the mediaProjection type may only be used once we actually hold
     * a projection grant. Declaring it unconditionally throws
     *   SecurityException: Starting FGS with type mediaProjection ...
     *   requires permissions FOREGROUND_SERVICE_MEDIA_PROJECTION and
     *   (CAPTURE_VIDEO_OUTPUT | project_media)
     * which crashed the whole app on "start watching". So: dataSync always,
     * mediaProjection only after consent.
     */
    private void enterForeground() {
        enterForeground(false);
    }

    /**
     * @param wantProjection true when we are about to create a MediaProjection.
     *
     * Chicken-and-egg note: creating a projection REQUIRES the service to
     * already be foreground with the mediaProjection type
     *   (SecurityException: Media projections require a foreground service of
     *    type FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
     * so the caller must promote the service BEFORE calling getMediaProjection().
     */
    private void enterForeground(boolean wantProjection) {
        Notification n = buildNotification(sCaptured.size());
        if (wantProjection) {
            sWantProjectionType = true;
        }
        // Remember across re-promotions: the system resets the service types to
        // the manifest default when it re-delivers onStartCommand, which is why
        // the mediaProjection flag appeared to "drop" after switching apps.
        // While a projection is alive the service MUST stay mediaProjection
        // typed: the system tears the projection down when the type is dropped,
        // which is exactly why consent appeared to "expire" after switching
        // apps.
        boolean keepProjection = sWantProjectionType || ScreenCapture.hasConsent(this);
        if (Build.VERSION.SDK_INT >= 29) {
            if (keepProjection) {
                int both = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                try {
                    startForeground(NOTIFICATION_ID, n, both);
                    Log.i(TAG, "foreground types=both (mediaProjection ready)");
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "mediaProjection foreground type not allowed yet", e);
                    // consent is gone: forget the request so we stop retrying
                    sWantProjectionType = false;
                }
            }
            try {
                startForeground(NOTIFICATION_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                return;
            } catch (Exception e) {
                Log.w(TAG, "foreground with dataSync type failed, falling back", e);
            }
        }
        startForeground(NOTIFICATION_ID, n);
    }

    /**
     * Re-promote after MediaProjection consent was granted, so the capture can
     * legally run under a mediaProjection-typed foreground service.
     */
    public static void onConsentGranted(Context ctx) {
        Intent i = new Intent(ctx, ShotWatcherService.class);
        i.setAction(ACTION_START + ".consent");
        try {
            ctx.startService(i);
        } catch (Exception ignored) { }
    }

    /**
     * Promote the service to the mediaProjection foreground type so the very
     * next getMediaProjection() call is allowed. Must be called BEFORE creating
     * the projection.
     */
    public static void prepareProjection(Context ctx) {
        Intent i = new Intent(ctx, ShotWatcherService.class);
        i.setAction(ACTION_START + ".prepare");
        try {
            ctx.startService(i);
        } catch (Exception ignored) { }
    }

    private void registerObserver() {
        observer = new ContentObserver(bg) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                onMediaChanged();
            }

            @Override
            public void onChange(boolean selfChange) {
                onMediaChanged();
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
        } catch (Exception e) {
            Log.w(TAG, "registerContentObserver failed", e);
        }
        // Safety net: MediaStore change notifications can be coalesced or
        // dropped on some ROMs, so also poll periodically while running.
        bg.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) {
                    return;
                }
                onMediaChanged();
                bg.postDelayed(this, 2000);
            }
        }, 2000);
    }

    private long lastScan;
    private boolean scanning;

    private void onMediaChanged() {
        long now = System.currentTimeMillis();
        if (scanning || now - lastScan < 400) {
            return;   // debounce: MediaStore fires bursts
        }
        scanning = true;
        lastScan = now;
        try {
            List<MediaStoreUtil.Shot> recent = MediaStoreUtil.recentImages(this, 6);
            boolean added = false;
            // newest-first; anything newer than lastSeenId is new
            List<MediaStoreUtil.Shot> fresh = new ArrayList<>();
            for (MediaStoreUtil.Shot s : recent) {
                if (s.id <= lastSeenId) {
                    break;
                }
                // only auto-collect actual screenshots
                if (s.screenshot) {
                    fresh.add(s);
                }
            }
            if (!recent.isEmpty()) {
                long newest = recent.get(0).id;
                if (newest > lastSeenId) {
                    lastSeenId = newest;
                }
            }
            // process oldest-first so the list stays chronological
            for (int i = fresh.size() - 1; i >= 0; i--) {
                MediaStoreUtil.Shot s = fresh.get(i);
                boolean dup = false;
                for (MediaStoreUtil.Shot e : sCaptured) {
                    if (e.id == s.id) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    sCaptured.add(s);
                    added = true;
                }
            }
            if (added) {
                persist();
                updateNotification();
                // Keep the bubble visible: it is the primary entry point and the
                // user does not want to re-enable it from the notification after
                // every shot. Our own captures never contain it (it is hidden
                // only while grabbing a frame); for system screenshots the user
                // can long-press to hide it.
                BubbleService.refreshBadge(this);
            }        } finally {
            scanning = false;
        }
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(sCaptured.size()));
        }
    }

    private Notification buildNotification(int count) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "截屏监听", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("监听新截图，用于字幕拼接");
                nm.createNotificationChannel(ch);
            }
        }
        String text = count > 0
                ? ("已捕获 " + count + " 张截图，点按开始拼接")
                : "正在监听新截图…";
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        // tap -> open the stitch editor directly
        Intent open = new Intent(this, StitchActivity.class);
        open.putExtra(StitchActivity.EXTRA_FROM_CAPTURED, true);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("字幕拼接")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true);

        // Action 1: stitch now
        Intent stitch = new Intent(this, StitchActivity.class);
        stitch.putExtra(StitchActivity.EXTRA_FROM_CAPTURED, true);
        stitch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent piStitch = PendingIntent.getActivity(this, 1, stitch, piFlags);
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_crop),
                "进入拼接", piStitch).build());

        // Action 2: toggle the floating bubble (recovers it after a long-press
        // hide). Both actions are always present; they are cheap and idempotent.
        Intent showB = new Intent(this, ShotWatcherService.class);
        showB.setAction(ACTION_SHOW_BUBBLE);
        PendingIntent piShow = PendingIntent.getService(this, 2, showB, piFlags);
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_add),
                "显示悬浮窗", piShow).build());

        Intent hideB = new Intent(this, ShotWatcherService.class);
        hideB.setAction(ACTION_HIDE_BUBBLE);
        PendingIntent piHide = PendingIntent.getService(this, 3, hideB, piFlags);
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "隐藏悬浮窗", piHide).build());

        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(Notification.PRIORITY_LOW);
        }
        if (Build.VERSION.SDK_INT >= 21) {
            b.setVisibility(Notification.VISIBILITY_PUBLIC);
        }
        return b.build();
    }

    /**
     * Append a screenshot produced by our own capture (MediaProjection) to the
     * captured list. These live in app cache, so they are addressed by path and
     * survive until the cache is trimmed.
     */
    public static void addCapturedFile(Context ctx, java.io.File f) {
        if (f == null || !f.exists()) {
            return;
        }
        sCaptured.add(MediaStoreUtil.Shot.ofFile(f));
        ShotStore.save(ctx, sCaptured);
        MediaStoreUtil.trimCache(ctx, 40);
        // Refresh the notification WITHOUT re-entering onStartCommand in a way
        // that re-promotes foreground types: restarting the service here used to
        // reset the mediaProjection type and kill the live capture session, which
        // looked like "must re-authorize after every screenshot".
        if (isRunning) {
            Intent i = new Intent(ctx, ShotWatcherService.class);
            i.setAction(ACTION_CLEAR + ".refresh");
            try {
                ctx.startService(i);
            } catch (Exception ignored) { }
        }
    }

    /** Rebuild the ongoing notification (e.g. after the bubble was hidden). */
    public static void refreshNotification(Context ctx) {
        if (!isRunning) {
            return;
        }
        Intent i = new Intent(ctx, ShotWatcherService.class);
        i.setAction(ACTION_SHOW_BUBBLE + ".refresh");
        try {
            ctx.startService(i);
        } catch (Exception ignored) { }
    }

    /**
     * Push a "list cleared" update to the running service so the notification
     * stops advertising shots that no longer exist.
     */
    public static void notifyCleared(Context ctx) {
        Intent i = new Intent(ctx, ShotWatcherService.class);
        i.setAction(ACTION_CLEAR);
        try {
            ctx.startService(i);
        } catch (Exception ignored) { }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // The system may reclaim the projection under memory pressure; refresh
        // our foreground types to match reality.
        if (!ScreenCapture.hasConsent(this)) {
            sWantProjectionType = false;
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        ShotStore.setWatcherRunning(this, false);
        persist();
        if (observer != null) {
            try {
                getContentResolver().unregisterContentObserver(observer);
            } catch (Exception ignored) { }
            observer = null;
        }
        if (thread != null) {
            thread.quitSafely();
        }
        BubbleService.hideBadge(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Start the watcher (foreground service; caller must hold FGS permission). */
    public static void start(Context ctx) {
        Intent i = new Intent(ctx, ShotWatcherService.class);
        i.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, ShotWatcherService.class);
        i.setAction(ACTION_STOP);
        try {
            ctx.startService(i);
        } catch (Exception ignored) { }
    }
}
