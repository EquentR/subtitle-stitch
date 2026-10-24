package com.dsh.subtitlestitch;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Main screen: permission status, the screenshot watcher toggle, and the entry
 * points into stitching.
 */
public class MainActivity extends Activity {

    private static final int REQ_MEDIA = 10;
    private static final int REQ_NOTIF = 11;
    private static final int REQ_PROJECTION = 12;

    /** EXTRA: open this activity just to collect MediaProjection consent. */
    public static final String EXTRA_REQUEST_CAPTURE = "request_capture";

    private TextView statusText;
    private TextView watchStateText;
    private Button watchToggleBtn;
    private Button openFromCaptured;
    private Button projectionBtn;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Set while the system capture-consent dialog is on screen. */
    private boolean awaitingProjection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        // Restore persisted state after a process restart: the in-memory
        // statics are wiped when the OS kills us, which made the counter and
        // the running flag reset every launch.
        if (ShotWatcherService.capturedCount() == 0) {
            java.util.List<MediaStoreUtil.Shot> restored = ShotStore.load(this);
            if (!restored.isEmpty()) {
                ShotWatcherService.setCaptured(restored);
            }
        }
        if (!ShotWatcherService.isRunning && ShotStore.isWatcherRunning(this)) {
            // Service was running before we died: it is START_STICKY and will be
            // recreated by the system, so reflect that intent in the UI.
            ShotWatcherService.isRunning = isServiceActuallyRunning();
        }
        setContentView(buildUi());
        refreshState();
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_REQUEST_CAPTURE, false)) {
            requestProjection();
        }
    }

    /** Ask the system for a one-time screen-capture (MediaProjection) consent. */
    private void requestProjection() {
        if (android.os.Build.VERSION.SDK_INT < 21) {
            toast("此系统版本不支持屏幕捕捉");
            return;
        }
        try {
            android.media.projection.MediaProjectionManager mgr =
                    ScreenCapture.MediaProjectionHolder.manager(this);
            if (mgr == null) {
                toast("当前设备不支持屏幕捕捉");
                return;
            }
            awaitingProjection = true;
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION);
        } catch (Exception e) {
            toast("无法请求屏幕捕捉权限");
        }
    }


    /**
     * Create the MediaProjection from the consent result.
     *
     * Android 14+ throws unless the calling process already owns a RUNNING
     * foreground service, which is why calling getMediaProjection() straight
     * away produced "授权失败". We therefore (re)start the watcher service
     * first, give it a moment to promote itself, and only then create the
     * projection -- retrying a few times to absorb scheduling delay.
     */
    /**
     * Create the MediaProjection from the consent result.
     *
     * Android 14+ requires the calling process to already own a RUNNING
     * foreground service, so we start the watcher first and only then create the
     * projection. NOTE: getMediaProjection() is valid exactly ONCE per consent
     * token -- if it fails we must ask the user again rather than retry, or the
     * retries would all fail and look like "授权失败".
     */
    private void obtainProjection(int resultCode, Intent consentData) {
        if (!isServiceActuallyRunning()) {
            ShotWatcherService.start(this);
        }
        // Promote the service to the mediaProjection foreground type FIRST:
        // getMediaProjection() throws SecurityException unless the service is
        // already running as FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.
        ShotWatcherService.prepareProjection(this);
        ui.postDelayed(() -> {
            try {
                android.media.projection.MediaProjection p =
                        ScreenCapture.MediaProjectionHolder.create(this, resultCode, consentData);
                android.util.Log.i("SubtitleStitch", "projection created: " + p);
                ShotWatcherService.onConsentGranted(this);
                toast("已授权截屏，可以点悬浮球上的圆点截图");
            } catch (Throwable e) {
                android.util.Log.w("SubtitleStitch", "getMediaProjection failed", e);
                toast("授权失败：" + e.getClass().getSimpleName());
            }
            refreshState();
        }, 400);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        android.util.Log.i("SubtitleStitch", "onActivityResult req=" + req
                + " res=" + res + " data=" + (data != null));
        if (req != REQ_PROJECTION) {
            return;
        }
        awaitingProjection = false;
        if (res == RESULT_OK && data != null) {
            obtainProjection(res, data);
        } else {
            toast("未授权截屏");
            refreshState();
        }
    }

    /** Ask ActivityManager whether our watcher service is alive right now. */
    private boolean isServiceActuallyRunning() {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            for (android.app.ActivityManager.RunningServiceInfo info
                    : am.getRunningServices(Integer.MAX_VALUE)) {
                if (info.service != null
                        && ShotWatcherService.class.getName().equals(info.service.getClassName())) {
                    return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
        // Keep the counters live while the screen is visible: the watcher may
        // capture new shots at any moment, and we do not want to show a stale
        // count until the next resume.
        ui.removeCallbacks(stateTicker);
        ui.postDelayed(stateTicker, 1000);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(stateTicker);
        super.onPause();
    }

    private final Runnable stateTicker = new Runnable() {
        @Override
        public void run() {
            refreshState();
            ui.postDelayed(this, 1000);
        }
    };

    private int dp(float v) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private View buildUi() {
        ScrollView scroller = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // Pad by the system-bar insets so the content never slides under the
        // status bar (edge-to-edge is the default from targetSdk 35 onward).
        int baseTop = dp(24);
        int baseBottom = dp(24);
        root.setPadding(dp(20), baseTop, dp(20), baseBottom);
        root.setBackgroundColor(Color.parseColor("#F5F6F8"));
        if (Build.VERSION.SDK_INT >= 23) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = android.graphics.Insets.NONE;
                if (Build.VERSION.SDK_INT >= 30) {
                    bars = insets.getInsets(
                            android.view.WindowInsets.Type.statusBars()
                                    | android.view.WindowInsets.Type.navigationBars()
                                    | android.view.WindowInsets.Type.displayCutout());
                } else {
                    bars = android.graphics.Insets.of(
                            insets.getSystemWindowInsetLeft(),
                            insets.getSystemWindowInsetTop(),
                            insets.getSystemWindowInsetRight(),
                            insets.getSystemWindowInsetBottom());
                }
                v.setPadding(dp(20) + bars.left, baseTop + bars.top,
                        dp(20) + bars.right, baseBottom + bars.bottom);
                return insets;
            });
        }
        scroller.addView(root);

        TextView title = new TextView(this);
        title.setText("字幕拼接");
        title.setTextSize(24);
        title.setTextColor(Color.parseColor("#1B1F24"));
        title.setGravity(Gravity.START);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("把多张截图纵向拼成一张长图，保留台词字幕");
        sub.setTextSize(13);
        sub.setTextColor(Color.parseColor("#6B7280"));
        sub.setPadding(0, dp(4), 0, dp(18));
        root.addView(sub);

        // ---- permission card
        LinearLayout card = card(root);
        TextView permTitle = new TextView(this);
        permTitle.setText("① 所需权限");
        permTitle.setTextSize(15);
        permTitle.setTextColor(Color.parseColor("#1B1F24"));
        card.addView(permTitle);

        statusText = new TextView(this);
        statusText.setTextSize(13);
        statusText.setTextColor(Color.parseColor("#6B7280"));
        statusText.setPadding(0, dp(6), 0, dp(10));
        card.addView(statusText);

        Button btnPerm = button("授予权限", false);
        btnPerm.setOnClickListener(v -> requestAllPermissions());
        card.addView(btnPerm);

        // ---- watcher card
        LinearLayout card2 = card(root);
        TextView watchTitle = new TextView(this);
        watchTitle.setText("② 自动捕获截图");
        watchTitle.setTextSize(15);
        watchTitle.setTextColor(Color.parseColor("#1B1F24"));
        card2.addView(watchTitle);

        TextView watchDesc = new TextView(this);
        watchDesc.setText("开启后会在后台监听新截图，并弹出悬浮球提醒");
        watchDesc.setTextSize(13);
        watchDesc.setTextColor(Color.parseColor("#6B7280"));
        watchDesc.setPadding(0, dp(6), 0, dp(10));
        card2.addView(watchDesc);

        watchStateText = new TextView(this);
        watchStateText.setTextSize(13);
        watchStateText.setTextColor(Color.parseColor("#1B1F24"));
        watchStateText.setPadding(0, 0, 0, dp(6));
        card2.addView(watchStateText);

        watchToggleBtn = button("开启监听", true);
        watchToggleBtn.setOnClickListener(v -> toggleWatch());
        card2.addView(watchToggleBtn);

        // Quick-settings tile shortcut. On API 33+ we can ask the system to
        // offer the tile; otherwise the user adds it manually.
        // MediaProjection consent: needed for the bubble's own capture button.
        projectionBtn = button("授权悬浮球自动截图", false);
        projectionBtn.setOnClickListener(v -> requestProjection());
        card2.addView(projectionBtn);

        Button tileBtn = button("添加到控制中心快捷开关", false);
        tileBtn.setOnClickListener(v -> {
            WatchTileService.requestAddTile(this);
            toast("下拉控制中心 → 编辑 → 添加「字幕拼接」");
        });
        card2.addView(tileBtn);

        // ---- stitch card
        LinearLayout card3 = card(root);
        TextView stitchTitle = new TextView(this);
        stitchTitle.setText("③ 开始拼接");
        stitchTitle.setTextSize(15);
        stitchTitle.setTextColor(Color.parseColor("#1B1F24"));
        card3.addView(stitchTitle);

        openFromCaptured = button("拼接已捕获的截图", true);
        openFromCaptured.setOnClickListener(v -> {
            Intent i = new Intent(this, StitchActivity.class);
            i.putExtra(StitchActivity.EXTRA_FROM_CAPTURED, true);
            startActivity(i);
        });
        card3.addView(openFromCaptured);

        Button pick = button("从相册选择图片", false);
        pick.setOnClickListener(v -> {
            Intent i = new Intent(this, StitchActivity.class);
            i.putExtra(StitchActivity.EXTRA_FROM_CAPTURED, false);
            startActivity(i);
        });
        card3.addView(pick);

        // Let the user drop the captured list. Necessary because screenshots can
        // be deleted from the gallery while we still hold their MediaStore ids;
        // opening the editor then fails to load and looks broken.
        Button clear = button("清空已捕获的截图", false);
        clear.setOnClickListener(v -> confirmClear());
        card3.addView(clear);

        return scroller;
    }

    private void confirmClear() {
        int n = ShotWatcherService.capturedCount();
        if (n == 0) {
            toast("当前没有已捕获的截图");
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("清空已捕获的截图？")
                .setMessage("将清除记录的 " + n + " 张截图（不会删除相册里的原图）。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> {
                    ShotWatcherService.clearCaptured();
                    ShotStore.clear(this);
                    BubbleService.hideBadge(this);
                    ShotWatcherService.notifyCleared(this);
                    refreshState();
                    toast("已清空");
                })
                .show();
    }

    /** Start/stop the screenshot watcher. */
    private void toggleWatch() {
        boolean running = isServiceActuallyRunning();
        ShotWatcherService.isRunning = running;
        android.util.Log.i("SubtitleStitch", "toggleWatch clicked, running=" + running);
        if (running) {
            ShotWatcherService.stop(this);
            toast("已停止监听");
            ui.postDelayed(this::refreshState, 400);
            return;
        }
        if (!MediaStoreUtil.canReadImages(this)) {
            toast("请先授予「读取图片」权限");
            requestMediaPermission();
            return;
        }
        ShotWatcherService.start(this);
        BubbleService.showBadge(this, ShotWatcherService.capturedCount());
        toast("已开启监听，去别的 App 截几张图试试");
        ui.postDelayed(this::refreshState, 600);
    }

    private LinearLayout card(LinearLayout parent) {        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(Color.WHITE);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        parent.addView(c, lp);
        return c;
    }

    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        if (primary) {
            b.setBackgroundColor(Color.parseColor("#1F6FEB"));
            b.setTextColor(Color.WHITE);
        } else {
            b.setBackgroundColor(Color.parseColor("#E8EEF9"));
            b.setTextColor(Color.parseColor("#1F6FEB"));
        }
        return b;
    }

    private void refreshState() {
        if (statusText == null) {
            return;
        }
        boolean media = MediaStoreUtil.canReadImages(this);
        boolean overlay = BubbleService.canDrawOverlays(this);
        StringBuilder sb = new StringBuilder();
        sb.append(media ? "✓ 读取图片" : "✗ 读取图片（必需）");
        sb.append("\n");
        sb.append(overlay ? "✓ 悬浮窗" : "✗ 悬浮窗（悬浮球需要）");
        statusText.setText(sb.toString());
        // Authoritative running state: ask the system, not a static flag that a
        // process restart would have cleared.
        boolean running = isServiceActuallyRunning();
        ShotWatcherService.isRunning = running;
        if (watchToggleBtn != null) {
            watchToggleBtn.setText(running ? "停止监听" : "开启监听");
            watchToggleBtn.setBackgroundColor(running
                    ? Color.parseColor("#E8EEF9") : Color.parseColor("#1F6FEB"));
            watchToggleBtn.setTextColor(running
                    ? Color.parseColor("#1F6FEB") : Color.WHITE);
        }
        if (watchStateText != null) {
            int n = ShotWatcherService.capturedCount();
            if (running) {
                watchStateText.setText("状态：监听中 · 已捕获 " + n + " 张");
                watchStateText.setTextColor(Color.parseColor("#1F6FEB"));
            } else {
                watchStateText.setText("状态：未开启");
                watchStateText.setTextColor(Color.parseColor("#6B7280"));
            }
        }
        if (projectionBtn != null) {
            projectionBtn.setText(ScreenCapture.hasConsent(this)
                    ? "✓ 已授权自动截图（重启 App 后需重新授权）"
                    : "授权悬浮球自动截图");
        }
        if (openFromCaptured != null) {
            int n = ShotWatcherService.capturedCount();
            openFromCaptured.setText(n > 0
                    ? ("拼接已捕获的截图（" + n + " 张）")
                    : "拼接已捕获的截图");
        }
    }

    private String mediaPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_IMAGES"
                : "android.permission.READ_EXTERNAL_STORAGE";
    }

    private void requestMediaPermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{mediaPermission()}, REQ_MEDIA);
        }
    }

    private void requestAllPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotif()) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        }
        if (!MediaStoreUtil.canReadImages(this)) {
            requestMediaPermission();
        }
        if (!BubbleService.canDrawOverlays(this)) {
            BubbleService.requestOverlayPermission(this);
            Toast.makeText(this, "请允许「显示在其他应用上层」", Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasNotif() {
        return checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        refreshState();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
