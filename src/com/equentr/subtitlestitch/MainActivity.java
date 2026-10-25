package com.equentr.subtitlestitch;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private Button bubbleBtn;
    private TextView capturedText;
    private TextView captureHint;
    private Button permissionBtn;
    private Button clearBtn;
    private Button pickBtn;
    private String lastUiState;
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
            startActivityForResult(ScreenCapture.createConsentIntent(this), REQ_PROJECTION);
            refreshState();
        } catch (Exception e) {
            awaitingProjection = false;
            refreshState();
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
                // Open the single long-lived capture session now; every later
                // screenshot reads a frame from it instead of creating a new
                // VirtualDisplay (which Android 14+ forbids and punishes by
                // revoking the projection).
                ScreenCapture.closeSession();
                ScreenCapture.openSession(this, p);
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

    /** True when the active configuration is dark. */
    private boolean isDark() {
        int mode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private int cText() {
        return isDark() ? Color.parseColor("#E8EAED") : Color.parseColor("#1B1F24");
    }

    private int cSubtle() {
        return isDark() ? Color.parseColor("#9AA0A6") : Color.parseColor("#6B7280");
    }

    private int cCard() {
        return isDark() ? Color.parseColor("#1F2226") : Color.WHITE;
    }

    private int cPage() {
        return isDark() ? Color.parseColor("#111316") : Color.parseColor("#F5F6F8");
    }

    private int dp(float v) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private View buildUi() {
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(true);
        scroller.setBackgroundColor(cPage());
        scroller.setVerticalScrollBarEnabled(false);

        // Insets belong to the viewport, so the last action scrolls above system bars.
        scroller.setOnApplyWindowInsetsListener((v, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left, top, right, bottom);
            return insets.consumeSystemWindowInsets();
        });
        // Center a readable column on tablets; calculate from the measured viewport
        // so rotations and split-screen resizing need no Activity recreation.
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(24));
        android.widget.FrameLayout.LayoutParams contentParams = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        frame.addView(root, contentParams);
        frame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = Math.min(r - l, dp(640));
            if (width > 0 && root.getLayoutParams().width != width) {
                root.getLayoutParams().width = width;
                root.requestLayout();
            }
        });
        scroller.addView(frame);

        TextView eyebrow = label("SUBTITLE STITCH", 11, cAccent());
        eyebrow.setLetterSpacing(0.16f);
        eyebrow.setTypeface(null, Typeface.BOLD);
        root.addView(eyebrow);
        TextView title = label("字幕拼接", 30, cText());
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, dp(6), 0, dp(6));
        title.setAccessibilityHeading(true);
        root.addView(title);
        TextView subtitle = label("把喜欢的台词，连成一张长图。", 14, cSubtle());
        subtitle.setPadding(0, 0, 0, dp(24));
        root.addView(subtitle);

        LinearLayout stitch = card(root);
        stitch.setBackground(rounded(isDark() ? Color.parseColor("#192A42")
                : Color.parseColor("#EAF2FF"), 24));
        heading(stitch, "开始创作", "选择截图，裁出字幕，再拼成一张长图。");
        capturedText = label("", 36, cAccent());
        capturedText.setTypeface(null, Typeface.BOLD);
        capturedText.setPadding(0, dp(16), 0, 0);
        stitch.addView(capturedText);
        captureHint = label("", 13, cSubtle());
        captureHint.setPadding(0, dp(2), 0, dp(8));
        stitch.addView(captureHint);
        openFromCaptured = button("拼接已捕获的截图", true);
        openFromCaptured.setOnClickListener(v -> openEditor(true));
        stitch.addView(openFromCaptured);
        pickBtn = button("从相册选择图片", false);
        pickBtn.setOnClickListener(v -> openEditor(false));
        stitch.addView(pickBtn);
        clearBtn = button("清空捕获记录", false);
        clearBtn.setOnClickListener(v -> confirmClear());
        styleQuiet(clearBtn);
        stitch.addView(clearBtn);

        LinearLayout watcher = card(root);
        heading(watcher, "自动收集截图", "开启监听后，在其他 App 截图会自动收集到这里。");
        watchStateText = label("", 12, cSubtle());
        watchStateText.setPadding(dp(12), dp(7), dp(12), dp(7));
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stateParams.topMargin = dp(14);
        watcher.addView(watchStateText, stateParams);
        watchToggleBtn = button("开启监听", false);
        watchToggleBtn.setOnClickListener(v -> toggleWatch());
        watcher.addView(watchToggleBtn);

        LinearLayout permissions = card(root);
        heading(permissions, "权限与准备", "读取图片用于收集截图；悬浮窗权限用于显示悬浮球。");
        statusText = label("", 13, cSubtle());
        statusText.setLineSpacing(dp(5), 1f);
        statusText.setPadding(0, dp(12), 0, dp(4));
        permissions.addView(statusText);
        permissionBtn = button("授予所需权限", false);
        permissionBtn.setOnClickListener(v -> requestAllPermissions());
        permissions.addView(permissionBtn);

        LinearLayout tools = card(root);
        heading(tools, "悬浮球与快捷操作", "离开 App 后，用悬浮球截图或快速进入拼接。");
        bubbleBtn = button("开启悬浮球", false);
        bubbleBtn.setOnClickListener(v -> {
            boolean on = ShotStore.isBubbleEnabled(this);
            if (!on && !BubbleService.canDrawOverlays(this)) {
                BubbleService.requestOverlayPermission(this);
                toast("请先允许「显示在其他应用上层」");
                return;
            }
            ShotStore.setBubbleEnabled(this, !on);
            if (!on) {
                toast("悬浮窗已开启，退出 App 后会显示");
            } else {
                BubbleService.hideBadge(this);
                toast("悬浮窗已关闭");
            }
            refreshState();
        });
        tools.addView(bubbleBtn);
        projectionBtn = button("授权悬浮球截图", false);
        projectionBtn.setOnClickListener(v -> requestProjection());
        tools.addView(projectionBtn);
        TextView consentHint = label("屏幕截图授权在会话结束后需重新开启。", 12, cSubtle());
        consentHint.setPadding(dp(4), dp(8), dp(4), dp(4));
        tools.addView(consentHint);
        Button tileBtn = button("添加控制中心快捷开关", false);
        tileBtn.setOnClickListener(v -> {
            WatchTileService.requestAddTile(this);
            toast("下拉控制中心 → 编辑 → 添加「字幕拼接」");
        });
        tools.addView(tileBtn);

        TextView footer = label("截图 → 调整字幕区域 → 预览保存", 12, cSubtle());
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(8), 0, dp(8));
        root.addView(footer);
        scroller.requestApplyInsets();
        return scroller;
    }

    private void openEditor(boolean fromCaptured) {
        Intent intent = new Intent(this, StitchActivity.class);
        intent.putExtra(StitchActivity.EXTRA_FROM_CAPTURED, fromCaptured);
        startActivity(intent);
    }

    private TextView label(String text, float size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private void heading(LinearLayout parent, String title, String description) {
        TextView heading = label(title, 18, cText());
        heading.setTypeface(null, Typeface.BOLD);
        heading.setAccessibilityHeading(true);
        parent.addView(heading);
        TextView detail = label(description, 13, cSubtle());
        detail.setPadding(0, dp(6), 0, 0);
        detail.setLineSpacing(dp(3), 1f);
        parent.addView(detail);
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

    private int cAccent() {
        return Color.parseColor(isDark() ? "#A5C8FF" : "#245DC1");
    }

    private int cSoft() {
        return Color.parseColor(isDark() ? "#273345" : "#EFF3F9");
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = rounded(cCard(), 24);
        background.setStroke(dp(1), Color.parseColor(isDark() ? "#30363E" : "#E3E8EF"));
        card.setBackground(background);
        card.setPadding(dp(20), dp(20), dp(20), dp(16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(16);
        parent.addView(card, params);
        return card;
    }

    private Button button(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(null, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(52));
        button.setMinimumHeight(dp(52));
        button.setPadding(dp(16), dp(12), dp(16), dp(12));
        // Wrap content keeps labels visible with large accessibility font sizes.
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        button.setLayoutParams(params);
        button.setStateListAnimator(null);
        styleButton(button, primary);
        return button;
    }

    private void styleButton(Button button, boolean primary) {
        int fill = primary ? Color.parseColor("#245DC1") : cSoft();
        int foreground = primary ? Color.WHITE : cAccent();
        ColorStateList colors = new ColorStateList(
                new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {cSubtle(), foreground});
        button.setTextColor(colors);
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(
                isDark() ? 0x33FFFFFF : 0x220F3F80), rounded(fill, 14), null));
        button.setAlpha(button.isEnabled() ? 1f : 0.5f);
    }

    private void styleQuiet(Button button) {
        styleButton(button, false);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(
                isDark() ? 0x33FFFFFF : 0x220F3F80), rounded(Color.TRANSPARENT, 14), null));
        button.setTextColor(cSubtle());
    }

    private void refreshState() {
        if (statusText == null) {
            return;
        }
        boolean media = MediaStoreUtil.canReadImages(this);
        boolean overlay = BubbleService.canDrawOverlays(this);
        boolean notifications = Build.VERSION.SDK_INT < 33 || hasNotif();
        boolean running = isServiceActuallyRunning();
        ShotWatcherService.isRunning = running;
        int count = ShotWatcherService.capturedCount();
        boolean bubble = ShotStore.isBubbleEnabled(this);
        boolean consent = ScreenCapture.hasConsent(this);
        String state = media + ":" + overlay + ":" + notifications + ":" + running
                + ":" + count + ":" + bubble + ":" + consent + ":" + awaitingProjection;
        if (state.equals(lastUiState)) {
            return;
        }
        lastUiState = state;
        statusText.setText((media ? "✓  读取图片已授权" : "○  读取图片 · 监听截图需要")
                + "\n" + (overlay ? "✓  悬浮窗已授权" : "○  悬浮窗 · 使用悬浮球需要")
                + (Build.VERSION.SDK_INT >= 33 ? "\n" + (notifications
                        ? "✓  通知已授权" : "○  通知 · 后台状态提醒") : ""));
        permissionBtn.setVisibility(media && overlay && notifications ? View.GONE : View.VISIBLE);
        capturedText.setText(count + " 张截图");
        captureHint.setText(count > 0 ? "已准备好，继续拼接你的台词长图" : "暂无捕获截图，可以先从相册选择");
        openFromCaptured.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        styleButton(pickBtn, count == 0);
        clearBtn.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        watchToggleBtn.setText(running ? "停止监听" : "开启截图监听");
        styleButton(watchToggleBtn, false);
        watchStateText.setText(running ? "●  正在监听新截图" : "○  监听未开启");
        watchStateText.setTextColor(running
                ? Color.parseColor(isDark() ? "#8DDBB7" : "#226548") : cSubtle());
        watchStateText.setBackground(rounded(running
                ? Color.parseColor(isDark() ? "#203C32" : "#E6F4EC") : cSoft(), 10));
        bubbleBtn.setText(bubble ? "悬浮球已开启 · 点按关闭" : "开启悬浮球");
        projectionBtn.setText(consent ? "✓ 已授权悬浮球截图" : "授权悬浮球截图");
        projectionBtn.setEnabled(!consent && !awaitingProjection);
        styleButton(projectionBtn, false);
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
