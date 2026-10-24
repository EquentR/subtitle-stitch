package com.equentr.subtitlestitch;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Invisible trampoline that lets the floating bubble request MediaProjection
 * consent directly.
 *
 * A Service cannot show the system consent dialog, so the bubble starts this
 * Activity, which immediately launches the dialog and finishes. That removes
 * the need for the user to open the app by hand just to grant capture.
 */
public class ConsentProxyActivity extends Activity {

    private static final int REQ_PROJECTION = 21;
    private static final String TAG = "SubtitleStitch";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.media.projection.MediaProjectionManager mgr =
                ScreenCapture.MediaProjectionHolder.manager(this);
        if (mgr == null) {
            finish();
            return;
        }
        try {
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION);
        } catch (Exception e) {
            Log.w(TAG, "cannot start consent dialog", e);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PROJECTION) {
            finish();
            return;
        }
        Log.i(TAG, "proxy consent result=" + res + " data=" + (data != null));
        if (res == RESULT_OK && data != null) {
            // Promote the service before creating the projection (Android 14+
            // requires a mediaProjection-typed foreground service).
            if (!ShotWatcherService.isRunning) {
                ShotWatcherService.start(this);
            }
            ShotWatcherService.prepareProjection(this);
            final int rc = res;
            final Intent d = data;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    android.media.projection.MediaProjection p =
                            ScreenCapture.MediaProjectionHolder.create(
                                    getApplicationContext(), rc, d);
                    ScreenCapture.closeSession();
                    ScreenCapture.openSession(getApplicationContext(), p);
                    ShotWatcherService.onConsentGranted(getApplicationContext());
                    android.widget.Toast.makeText(getApplicationContext(),
                            "已授权自动截图", android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable e) {
                    Log.w(TAG, "proxy getMediaProjection failed", e);
                    android.widget.Toast.makeText(getApplicationContext(),
                            "授权失败：" + e.getClass().getSimpleName(),
                            android.widget.Toast.LENGTH_LONG).show();
                }
                finish();
            }, 400);
            return;
        }
        android.widget.Toast.makeText(this, "未授权截屏", android.widget.Toast.LENGTH_SHORT).show();
        finish();
    }
}
