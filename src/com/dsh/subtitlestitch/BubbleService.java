package com.dsh.subtitlestitch;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Floating badge: "已捕获 N 张 · 点击拼接".
 *
 * Shown by ShotWatcherService whenever new screenshots were captured. Tapping it
 * opens the stitch screen. Dragging moves it; it snaps to the nearest edge.
 *
 * Requires SYSTEM_ALERT_WINDOW. All failures are swallowed: the feature is an
 * enhancement, and the app still works through its normal launcher icon.
 */
public class BubbleService extends Service {

    public static final String ACTION_SHOW_BADGE = "com.dsh.subtitlestitch.SHOW_BADGE";
    public static final String EXTRA_COUNT = "count";
    public static final String ACTION_HIDE_BADGE = "com.dsh.subtitlestitch.HIDE_BADGE";
    public static final String ACTION_HIDE_FOR = "com.dsh.subtitlestitch.HIDE_FOR";
    public static final String EXTRA_HIDE_MS = "hide_ms";
    /** Set when the user explicitly asked for the bubble (notification action). */
    public static final String EXTRA_FORCE = "force_show";

    /** How long a long-press hides the bubble (long enough to take a shot). */
    public static final long TEMP_HIDE_MS = 10_000L;

    private static boolean sVisible = false;
    private static boolean sSuppressAfterScreenshot = false;

    private WindowManager wm;
    private View bubble;
    private WindowManager.LayoutParams lp;
    private TextView label;
    private TextView captureBtn;
    private int count;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Re-show the bubble after a temporary hide, unless it was hidden for good. */
    private void restoreAfterHide() {
        sSuppressAfterScreenshot = false;
        if (!canDrawOverlays(this)) {
            return;
        }
        if (bubble == null || !bubble.isAttachedToWindow()) {
            addBubble();
        }
        sVisible = true;
    }

    public static boolean canDrawOverlays(Context ctx) {
        if (Build.VERSION.SDK_INT >= 23) {
            return Settings.canDrawOverlays(ctx);
        }
        return true;
    }

    /** Request the overlay permission screen. */
    public static void requestOverlayPermission(Context ctx) {
        if (Build.VERSION.SDK_INT < 23 || canDrawOverlays(ctx)) {
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) { }
    }

    /** Show or update the badge. No-op (silently) without overlay permission. */
    public static void showBadge(Context ctx, int count) {
        if (!canDrawOverlays(ctx)) {
            return;
        }
        Intent i = new Intent(ctx, BubbleService.class);
        i.setAction(ACTION_SHOW_BADGE);
        i.putExtra(EXTRA_COUNT, count);
        try {
            ctx.startService(i);
        } catch (Exception ignored) { }
    }

    public static void hideBadge(Context ctx) {
        Intent i = new Intent(ctx, BubbleService.class);
        i.setAction(ACTION_HIDE_BADGE);
        try {
            ctx.startService(i);
        } catch (Exception ignored) { }
        sVisible = false;
    }

    /** Long-press action: hide the bubble until the user brings it back. */
    public static void hidePermanently(Context ctx) {
        Intent i = new Intent(ctx, BubbleService.class);
        i.setAction(ACTION_HIDE_BADGE);
        try {
            ctx.startService(i);
        } catch (Exception ignored) { }
        sVisible = false;
        sSuppressAfterScreenshot = false;
        ShotStore.setBubbleHidden(ctx, true);
    }

    /** Re-show the bubble (used by the notification action). */
    public static void showAgain(Context ctx) {
        sSuppressAfterScreenshot = false;
        ShotStore.setBubbleHidden(ctx, false);
        if (!canDrawOverlays(ctx)) {
            return;
        }
        // Start the service directly with a count: showBadge() would go through
        // onStartCommand, which drops the request while suppression is armed.
        Intent i = new Intent(ctx, BubbleService.class);
        i.setAction(ACTION_SHOW_BADGE);
        i.putExtra(EXTRA_COUNT, ShotWatcherService.capturedCount());
        i.putExtra(EXTRA_FORCE, true);
        try {
            ctx.startService(i);
        } catch (Exception ignored) { }
    }

    /**
     * Called right after a screenshot was detected. The capture has already
     * happened by then, so re-showing the bubble would just put it in the NEXT
     * shot as well; we suppress it for a short while instead.
     */
    public static void onNewScreenshot(Context ctx) {
        sSuppressAfterScreenshot = true;
        // hide briefly so the bubble does not land in the NEXT shot either
        Intent i = new Intent(ctx, BubbleService.class);
        i.setAction(ACTION_HIDE_FOR);
        i.putExtra(EXTRA_HIDE_MS, 3000L);
        try {
            ctx.startService(i);
        } catch (Exception ignored) { }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null && intent.getAction() != null
                ? intent.getAction() : ACTION_SHOW_BADGE;
        int newCount = intent != null ? intent.getIntExtra(EXTRA_COUNT, 0) : 0;
        android.util.Log.i("SubtitleStitch", "BubbleService onStartCommand action=" + action
                + " count=" + newCount + " bubble=" + (bubble != null)
                + " suppress=" + sSuppressAfterScreenshot
                + " canOverlay=" + canDrawOverlays(this));

        if (ACTION_HIDE_BADGE.equals(action)) {
            removeBubble();
            sVisible = false;
            return START_NOT_STICKY;
        }
        if (ACTION_HIDE_FOR.equals(action)) {
            long ms = intent != null ? intent.getLongExtra(EXTRA_HIDE_MS, TEMP_HIDE_MS) : TEMP_HIDE_MS;
            removeBubble();
            main.postDelayed(this::restoreAfterHide, ms);
            return START_NOT_STICKY;
        }
        if (!canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean force = intent != null && intent.getBooleanExtra(EXTRA_FORCE, false);
        if (force) {
            // the user explicitly asked for the bubble from the notification
            sSuppressAfterScreenshot = false;
        }
        // Respect a long-press hide: do not resurrect the bubble unless the user
        // asked for it (survives restarts via ShotStore).
        if (!force && ShotStore.isBubbleHidden(this)) {
            removeBubble();
            sVisible = false;
            return START_NOT_STICKY;
        }
        // Do not pop the bubble back up immediately after a screenshot; the
        // capture already happened, so showing it now only pollutes the image.
        if (!force && sSuppressAfterScreenshot) {
            sSuppressAfterScreenshot = false;
            count = newCount;
            return START_NOT_STICKY;
        }
        // Guard against duplicate bubbles: if a view already exists, reuse it.
        // (Two Bubbles could appear when the service was recreated while an old
        // view was still attached.)
        if (bubble != null && bubble.isAttachedToWindow()) {
            count = newCount;
            updateLabel();
        } else {
            removeBubble();
            count = newCount;
            addBubble();
        }
        sVisible = true;
        return START_STICKY;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void addBubble() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) {
            stopSelf();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6202A37);
        bg.setCornerRadius(dp(22));
        root.setBackground(bg);

        label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(14), 0, dp(6), 0);
        root.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        // Dedicated capture button: hides the bubble, grabs one frame via
        // MediaProjection, then restores itself. This is what makes a totally
        // unobstructed screenshot possible without the user pressing anything.
        captureBtn = new TextView(this);
        captureBtn.setText("\u25CF");
        captureBtn.setTextColor(0xFFFFD666);
        captureBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        captureBtn.setGravity(Gravity.CENTER);
        captureBtn.setPadding(dp(8), 0, dp(14), 0);
        captureBtn.setOnClickListener(v -> captureNow());
        root.addView(captureBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        updateLabel();

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                dp(44),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        // NOTE: deliberately NOT FLAG_SECURE. With FLAG_SECURE the screenshot
        // pipeline paints a solid BLACK rectangle where the bubble is, which is
        // worse than the bubble itself. Instead we hide the bubble for a moment
        // around screenshot events (see hideForScreenshot()).
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(12);
        lp.y = dp(200);

        root.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;
            boolean longFired;
            final Runnable longPress = () -> {
                longFired = true;
                // Long-press hides the bubble for good, until the user taps the
                // "显示悬浮窗" action on the ongoing notification. This keeps the
                // screen unobstructed during a screenshot session.
                removeBubble();
                sVisible = false;
                toast("悬浮球已隐藏，可在通知栏恢复");
                ShotWatcherService.refreshNotification(BubbleService.this);
            };

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        longFired = false;
                        main.postDelayed(longPress, 600);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                            moved = true;
                            main.removeCallbacks(longPress);
                        }
                        lp.x = startX + (int) dx;
                        lp.y = startY + (int) dy;
                        try {
                            wm.updateViewLayout(bubble, lp);
                        } catch (Exception ignored) { }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        main.removeCallbacks(longPress);
                        if (longFired) {
                            return true;   // already handled
                        }
                        if (!moved) {
                            openStitch();
                        } else {
                            snapToEdge();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(longPress);
                        return true;
                }
                return false;
            }
        });

        bubble = root;
        try {
            wm.addView(bubble, lp);
        } catch (Exception e) {
            bubble = null;
            stopSelf();
        }
    }

    private void snapToEdge() {
        if (bubble == null || wm == null) {
            return;
        }
        int width = getResources().getDisplayMetrics().widthPixels;
        int bubbleW = bubble.getWidth() > 0 ? bubble.getWidth() : dp(120);
        lp.x = (lp.x + bubbleW / 2) < width / 2 ? dp(12) : width - bubbleW - dp(12);
        // keep inside the screen vertically
        int h = getResources().getDisplayMetrics().heightPixels;
        if (lp.y < 0) {
            lp.y = 0;
        }
        if (lp.y > h - dp(60)) {
            lp.y = h - dp(60);
        }
        try {
            wm.updateViewLayout(bubble, lp);
        } catch (Exception ignored) { }
    }

    /**
     * Capture the screen from the bubble.
     *
     * Sequence (all on the main thread until the capture thread takes over):
     *   1. hide the bubble view so it is not part of the frame
     *   2. wait a couple of frames for the window manager to actually remove it
     *   3. capture one frame through MediaProjection
     *   4. re-show the bubble
     *   5. append the PNG to the captured list
     *
     * Without projection consent we fall back to asking for it: we cannot show a
     * dialog from a Service, so we bounce the user to MainActivity which owns
     * the consent request.
     */
    private void captureNow() {
        if (!ScreenCapture.hasConsent(this)) {
            // Ask for consent right here: an invisible proxy activity shows the
            // system dialog and finishes, so the user never has to open the app.
            toast("请授权截屏");
            try {
                Intent i = new Intent(this, ConsentProxyActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                toast("无法打开授权窗口");
            }
            return;
        }
        // 1+2: hide, let the compositor drop it
        removeBubble();
        sVisible = false;
        main.postDelayed(() -> doCaptureAndRestore(), 180);
    }

    private void doCaptureAndRestore() {
        android.media.projection.MediaProjection projection = ScreenCapture.MediaProjectionHolder.get();
        new Thread(() -> {
            java.io.File f = null;
            try {
                f = ScreenCapture.captureOnce(getApplicationContext(), projection);
            } catch (ScreenCapture.NoConsentException e) {
                // consent vanished between check and use
            } catch (Exception ignored) { }
            final java.io.File out = f;
            main.post(() -> {
                if (out != null) {
                    ShotWatcherService.addCapturedFile(this, out);
                    toast("已截取一张");
                } else {
                    toast("截取失败，请重试");
                }
                // 4: restore the bubble
                count = ShotStore.load(this).size();
                addBubble();
                sVisible = true;
            });
        }, "bubble-capture").start();
    }

    private void toast(String msg) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) { }
    }

    private void updateLabel() {
        if (label == null) {
            return;
        }
        // Read the authoritative count from disk rather than trusting the value
        // captured when this intent was built: the bubble and the notification
        // are updated from different code paths, and the static in-memory list
        // is empty after a process restart. This keeps the bubble's number in
        // step with the notification and the app screen.
        int live = count;
        try {
            int persisted = ShotStore.load(this).size();
            if (persisted != live) {
                live = persisted;
                count = persisted;
            }
        } catch (Exception ignored) { }
        label.setText(live > 0
                ? ("已捕获 " + live + " 张 · 点此拼接")
                : "字幕拼接");
    }

    private void openStitch() {
        Intent i = new Intent(this, StitchActivity.class);
        i.putExtra(StitchActivity.EXTRA_FROM_CAPTURED, true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception ignored) { }
    }

    private void removeBubble() {
        if (bubble != null && wm != null) {
            try {
                wm.removeView(bubble);
            } catch (Exception ignored) { }
        }
        bubble = null;
    }

    @Override
    public void onDestroy() {
        removeBubble();
        sVisible = false;
        super.onDestroy();
    }
}
