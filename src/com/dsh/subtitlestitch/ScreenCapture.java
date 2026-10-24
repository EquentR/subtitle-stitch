package com.dsh.subtitlestitch;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * Screenshot capture driven by the app itself.
 *
 * Why MediaProjection: a third party cannot call the system's screenshot key or
 * broadcast (those are privileged), but MediaProjection -- the "screen recording"
 * facility -- gives us the framebuffer after a one-time user consent dialog. It
 * is NOT a runtime permission and needs no root.
 *
 * Flow used by the floating bubble:
 *   1. hide the bubble                       (so it is not in the frame)
 *   2. capture exactly one frame              (VirtualDisplay + ImageReader)
 *   3. release the virtual display
 *   4. re-show the bubble
 *   5. write the PNG and hand it to the watcher list
 *
 * Consent is per session: after the process dies the user must approve again.
 * That is an Android security invariant we cannot avoid.
 */
public final class ScreenCapture {

    private static final String TAG = "SubtitleStitch";

    private ScreenCapture() { }

    /** Thrown when we have no valid consent token yet. */
    public static final class NoConsentException extends Exception {
        public NoConsentException() { super("media projection consent required"); }
    }

    /**
     * Grab one screen frame and save it as a PNG in our capture directory.
     * Runs on a background thread; blocking is fine there.
     *
     * @return the written file, or null on failure
     */
    public static File captureOnce(Context ctx, MediaProjection projection) throws NoConsentException {
        if (projection == null) {
            throw new NoConsentException();
        }
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return null;
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        final int width = dm.widthPixels;
        final int height = dm.heightPixels;
        final int density = dm.densityDpi;

        HandlerThread ht = new HandlerThread("capture");
        ht.start();
        Handler handler = new Handler(ht.getLooper());
        ImageReader reader = null;
        VirtualDisplay vd = null;
        Bitmap bmp = null;
        try {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            vd = projection.createVirtualDisplay(
                    "stitch-capture",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);

            // give the display a couple of frames to mirror
            Image img = null;
            long deadline = System.currentTimeMillis() + 2000;
            while (img == null && System.currentTimeMillis() < deadline) {
                img = reader.acquireLatestImage();
                if (img == null) {
                    try { Thread.sleep(60); } catch (InterruptedException ignored) { }
                }
            }
            if (img == null) {
                Log.w(TAG, "captureOnce: no frame available");
                return null;
            }
            try {
                bmp = toBitmap(img);
            } finally {
                img.close();
            }
            return savePng(ctx, bmp);
        } catch (Exception e) {
            Log.w(TAG, "captureOnce failed", e);
            return null;
        } finally {
            if (bmp != null) {
                bmp.recycle();
            }
            try { if (vd != null) vd.release(); } catch (Exception ignored) { }
            try { if (reader != null) reader.close(); } catch (Exception ignored) { }
            ht.quitSafely();
        }
    }

    private static Bitmap toBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        int bmpWidth = image.getWidth() + rowPadding / pixelStride;

        Bitmap bmp = Bitmap.createBitmap(bmpWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buffer);
        if (bmpWidth == image.getWidth()) {
            return bmp;
        }
        Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, image.getWidth(), image.getHeight());
        bmp.recycle();
        return cropped;
    }

    private static File savePng(Context ctx, Bitmap bmp) {
        File dir = new File(ctx.getCacheDir(), "captured");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File out = new File(dir, "shot_" + System.currentTimeMillis() + ".png");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            return out;
        } catch (Exception e) {
            Log.w(TAG, "savePng failed", e);
            return null;
        }
    }

    /**
     * True if the projection is still usable. A projection dies when the user
     * revokes it, when the app goes to the background on some OEM builds, or
     * when the system reclaims it -- in all those cases the user must consent
     * again, so we surface that instead of failing silently on capture.
     */
    public static boolean isAlive(Context ctx) {
        return MediaProjectionHolder.get() != null;
    }

    /** True if we hold a usable consent token from this session. */
    public static boolean hasConsent(Context ctx) {
        return MediaProjectionHolder.get() != null;
    }

    /**
     * Consent result kept for the lifetime of the process. getMediaProjection()
     * may only be called ONCE per consent token, so if the projection is lost
     * (service restart) we cannot rebuild it from the old token -- the user has
     * to approve again. Persisting the token therefore adds nothing; instead we
     * surface the state honestly in the UI.
     */
    public static boolean consentExpiredAfterRestart() {
        return MediaProjectionHolder.get() == null;
    }

    /**
     * Holds the MediaProjection granted by the system consent dialog.
     *
     * The projection object lives in a static so the bubble (running in this
     * process) can use it without re-asking. It is cleared in onStop so we do
     * not leak a capture session.
     */
    public static final class MediaProjectionHolder {
        private static MediaProjection sProjection;
        private static MediaProjection.Callback sCallback;

        /**
         * Register the mandatory state callback once per projection. Without it
         * createVirtualDisplay() throws IllegalStateException on Android 14+.
         */
        private static void ensureCallback(MediaProjection p) {
            if (sCallback != null) {
                return;   // already registered for this projection
            }
            sCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    android.util.Log.w(TAG, "MediaProjection STOPPED by the system");
                    sProjection = null;
                    sCallback = null;
                }
            };
            // The callback must be registered on a handler with a Looper.
            p.registerCallback(sCallback,
                    new android.os.Handler(android.os.Looper.getMainLooper()));
        }

        private MediaProjectionHolder() { }

        public static MediaProjection get() {
            return sProjection;
        }

        public static void set(MediaProjection p) {
            sProjection = p;
        }

        public static void clear() {
            sProjection = null;
            sCallback = null;
        }

        /**
         * Consume the system consent result and create the projection.
         *
         * getMediaProjection() may be called only ONCE per consent token, so this
         * helper is the single entry point and reports the real failure reason
         * to the caller instead of a generic "failed".
         */
        public static MediaProjection create(Context ctx, int resultCode, Intent data)
                throws Exception {
            MediaProjectionManager mgr = manager(ctx);
            if (mgr == null) {
                throw new IllegalStateException("MediaProjectionManager unavailable");
            }
            MediaProjection p = mgr.getMediaProjection(resultCode, data);
            if (p == null) {
                throw new IllegalStateException("getMediaProjection returned null");
            }
            // Android 14+ REQUIRES a callback to be registered before any capture
            // starts:
            //   IllegalStateException: Must register a callback before starting
            //   capture, to manage resources in response to MediaProjection states.
            // Register it exactly once, here, on a main-thread handler.
            ensureCallback(p);
            sProjection = p;
            return p;
        }

        public static MediaProjectionManager manager(Context ctx) {
            return (MediaProjectionManager) ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        }
    }
}
