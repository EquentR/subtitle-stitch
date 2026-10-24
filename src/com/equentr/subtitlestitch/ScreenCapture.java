package com.equentr.subtitlestitch;

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
 * Screenshot capture through MediaProjection.
 *
 * THE key constraint (Android 14+):
 *   "Don't take multiple captures by invoking MediaProjection#createVirtualDisplay
 *    multiple times on the same instance."
 * A projection instance may own exactly ONE VirtualDisplay for its whole life.
 * Creating a fresh display per screenshot throws SecurityException AND revokes
 * the projection -- which looked like "have to re-authorise after every shot".
 *
 * So we keep ONE long-lived VirtualDisplay + ImageReader for the session and
 * simply grab the newest frame each time the user asks for a shot. The session
 * is torn down only when the projection is revoked.
 *
 * MediaProjection is NOT a runtime permission: the user approves once via the
 * system dialog. No root required.
 */
public final class ScreenCapture {

    private static final String TAG = "SubtitleStitch";

    private ScreenCapture() { }

    /** Thrown when there is no usable consent token for this session. */
    public static final class NoConsentException extends Exception {
        public NoConsentException() { super("media projection consent required"); }
    }

    /**
     * A session owns the single VirtualDisplay for one MediaProjection.
     * Frames are read from the ImageReader on demand.
     */
    public static final class Session {
        private final MediaProjection projection;
        private final ImageReader reader;
        private final VirtualDisplay display;
        private final HandlerThread thread;
        private final int width;
        private final int height;
        private volatile boolean closed;

        private Session(MediaProjection p, ImageReader r, VirtualDisplay d,
                        HandlerThread t, int w, int h) {
            this.projection = p;
            this.reader = r;
            this.display = d;
            this.thread = t;
            this.width = w;
            this.height = h;
        }

        public int width() { return width; }

        public int height() { return height; }

        public boolean isAlive() {
            return !closed;
        }

        /**
         * Grab the most recent frame and save it as a PNG.
         * Safe to call repeatedly: the display is never recreated.
         */
        /**
         * FAST path: take the newest frame into a Bitmap.
         *
         * The caller must keep any overlay hidden only until this returns; all
         * slow work (trim + encode) is deferred to {@link #encodeFile(Context,
         * Bitmap)} so the bubble can reappear almost immediately.
         */
        public Bitmap grabFrame() {
            if (closed) {
                return null;
            }
            Image img = null;
            final long t0 = android.os.SystemClock.elapsedRealtime();
            try {
                Image first = reader.acquireLatestImage();
                if (first != null) {
                    first.close();          // discard whatever was already queued
                }
                long deadline = System.currentTimeMillis() + 800;
                while (img == null && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(8); } catch (InterruptedException ignored) { }
                    img = reader.acquireLatestImage();
                }
                long tAcquire = android.os.SystemClock.elapsedRealtime();
                if (img == null) {
                    Log.w(TAG, "grabFrame: no frame after " + (tAcquire - t0) + "ms");
                    return null;
                }
                Bitmap bmp = toBitmap(img);
                Log.i(TAG, "FRAME TIMING acquire=" + (tAcquire - t0)
                        + "ms bitmap=" + (android.os.SystemClock.elapsedRealtime() - tAcquire)
                        + "ms");
                return bmp;
            } catch (Exception e) {
                Log.w(TAG, "grabFrame failed", e);
                return null;
            } finally {
                if (img != null) {
                    try { img.close(); } catch (Exception ignored) { }
                }
            }
        }

        /**
         * SLOW path: trim black bars and write the file. Safe to run after the
         * overlay has been shown again.
         */
        public File encodeFile(Context ctx, Bitmap bmp) {
            if (bmp == null) {
                return null;
            }
            long t0 = android.os.SystemClock.elapsedRealtime();
            try {
                Bitmap trimmed = StitchEngine.trimBlackBorders(bmp);
                long tTrim = android.os.SystemClock.elapsedRealtime();
                try {
                    File out = savePng(ctx, trimmed);
                    Log.i(TAG, "ENCODE TIMING trim=" + (tTrim - t0)
                            + "ms save=" + (android.os.SystemClock.elapsedRealtime() - tTrim)
                            + "ms");
                    return out;
                } finally {
                    if (trimmed != bmp) {
                        trimmed.recycle();
                    }
                }
            } finally {
                bmp.recycle();
            }
        }

        public File grab(Context ctx) {
            if (closed) {
                return null;
            }
            Image img = null;
            final long t0 = android.os.SystemClock.elapsedRealtime();
            try {
                // Drain buffered frames first: the reader may still hold frames
                // captured BEFORE the caller hid the floating bubble, and using
                // one of those baked the bubble (or a black box in its place)
                // into the screenshot.
                // acquireLatestImage() already discards older queued frames and
                // returns the newest one, so a separate drain loop only added
                // latency. We simply poll until a frame produced AFTER the caller
                // hid the overlay shows up.
                long deadline = System.currentTimeMillis() + 800;
                Image first = reader.acquireLatestImage();
                if (first != null) {
                    first.close();          // discard whatever was already queued
                }
                while (img == null && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(16); } catch (InterruptedException ignored) { }
                    img = reader.acquireLatestImage();
                }
                long tAcquire = android.os.SystemClock.elapsedRealtime();
                if (img == null) {
                    Log.w(TAG, "grab: no frame available after "
                            + (tAcquire - t0) + "ms");
                    return null;
                }
                Bitmap bmp = toBitmap(img);
                long tBitmap = android.os.SystemClock.elapsedRealtime();
                try {
                    // The mirrored content may be letterboxed inside the virtual
                    // display (e.g. a landscape app on a portrait-sized surface).
                    // Crop the uniform black bars so the stitched result has no
                    // giant empty margins.
                    Bitmap trimmed = StitchEngine.trimBlackBorders(bmp);
                    long tTrim = android.os.SystemClock.elapsedRealtime();
                    if (trimmed != bmp) {
                        try {
                            File outFile = savePng(ctx, trimmed);
                            long tSave2 = android.os.SystemClock.elapsedRealtime();
                            Log.i(TAG, "CAPTURE TIMING acquire=" + (tAcquire - t0)
                                    + "ms bitmap=" + (tBitmap - tAcquire)
                                    + "ms trim=" + (tTrim - tBitmap)
                                    + "ms save=" + (tSave2 - tTrim)
                                    + "ms total=" + (tSave2 - t0) + "ms");
                            return outFile;
                        } finally {
                            trimmed.recycle();
                        }
                    }
                    File outFile = savePng(ctx, bmp);
                    long tSave = android.os.SystemClock.elapsedRealtime();
                    Log.i(TAG, "CAPTURE TIMING acquire=" + (tAcquire - t0)
                            + "ms bitmap=" + (tBitmap - tAcquire)
                            + "ms trim=" + (tTrim - tBitmap)
                            + "ms save=" + (tSave - tTrim)
                            + "ms total=" + (tSave - t0) + "ms");
                    return outFile;
                } finally {
                    bmp.recycle();
                }
            } catch (Exception e) {
                Log.w(TAG, "grab failed", e);
                return null;
            } finally {
                if (img != null) {
                    try { img.close(); } catch (Exception ignored) { }
                }
            }
        }

        void shutdown() {
            if (closed) {
                return;
            }
            closed = true;
            try { display.release(); } catch (Exception ignored) { }
            try { reader.close(); } catch (Exception ignored) { }
            try { thread.quitSafely(); } catch (Exception ignored) { }
        }
    }

    /**
     * Current screen size in pixels, in the orientation the device is in now.
     *
     * getRealMetrics() reports the physical panel size and does not follow the
     * activity orientation, so it must not be used to size a capture surface.
     */
    public static int[] currentScreenSize(Context ctx) {
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        int[] out = new int[]{1080, 1920};
        if (wm == null) {
            return out;
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowMetrics metrics = wm.getMaximumWindowMetrics();
            android.graphics.Rect b = metrics.getBounds();
            // getMaximumWindowMetrics still returns the natural bounds; prefer the
            // configuration orientation to decide whether to swap.
            boolean landscape = ctx.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            int s = Math.min(b.width(), b.height());
            int l = Math.max(b.width(), b.height());
            out[0] = landscape ? l : s;
            out[1] = landscape ? s : l;
            return out;
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        boolean landscape = ctx.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int s = Math.min(dm.widthPixels, dm.heightPixels);
        int l = Math.max(dm.widthPixels, dm.heightPixels);
        out[0] = landscape ? l : s;
        out[1] = landscape ? s : l;
        return out;
    }

    /** Build the session for a freshly granted projection. Call once per consent. */
    public static Session openSession(Context ctx, MediaProjection projection)
            throws NoConsentException {
        if (projection == null) {
            throw new NoConsentException();
        }
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            throw new NoConsentException();
        }
        // Size the virtual display to the CURRENT screen orientation. Using the
        // real metrics of a portrait-default display gave a 1264x2780 surface even
        // in landscape, so landscape content was letterboxed with huge black
        // bars at top and bottom.
        int[] size = currentScreenSize(ctx);
        final int width = size[0];
        final int height = size[1];
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        final int density = dm.densityDpi;

        HandlerThread ht = new HandlerThread("capture");
        ht.start();
        Handler handler = new Handler(ht.getLooper());

        ImageReader reader = ImageReader.newInstance(width, height,
                PixelFormat.RGBA_8888, 2);
        // ONE VirtualDisplay per projection, reused for every screenshot.
        VirtualDisplay vd = projection.createVirtualDisplay(
                "stitch-capture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, handler);

        Session s = new Session(projection, reader, vd, ht, width, height);
        MediaProjectionHolder.setSession(s);
        Log.i(TAG, "capture session opened " + width + "x" + height);
        return s;
    }

    /** Close the current session (projection revoked or app shutting down). */
    public static void closeSession() {
        Session s = MediaProjectionHolder.getSession();
        if (s != null) {
            s.shutdown();
            MediaProjectionHolder.setSession(null);
            Log.i(TAG, "capture session closed");
        }
    }

    /** True while a capture session is usable. */
    public static boolean hasConsent(Context ctx) {
        Session s = MediaProjectionHolder.getSession();
        return s != null && s.isAlive();
    }

    private static Bitmap toBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        int bmpWidth = image.getWidth() + rowPadding / pixelStride;

        Bitmap bmp = Bitmap.createBitmap(bmpWidth, image.getHeight(),
                Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buffer);
        if (bmpWidth == image.getWidth()) {
            return bmp;
        }
        Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0,
                image.getWidth(), image.getHeight());
        bmp.recycle();
        return cropped;
    }

    private static File savePng(Context ctx, Bitmap bmp) {
        File dir = new File(ctx.getCacheDir(), "captured");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // JPEG, not PNG: PNG compression took ~1.4 s of the ~1.5 s capture
        // (PNG is lossless and re-compresses every pixel). A screenshot has no
        // transparency and quality 95 is visually identical, so JPEG at 95 is
        // the right trade-off here.
        File out = new File(dir, "shot_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos);
            return out;
        } catch (Exception e) {
            Log.w(TAG, "savePng failed", e);
            return null;
        }
    }

    /**
     * Holds the projection plus its single capture session.
     *
     * The projection lives for the process lifetime; it dies only when the system
     * revokes it (onStop) or the user stops capturing from the status bar.
     */
    public static final class MediaProjectionHolder {
        private static MediaProjection sProjection;
        private static MediaProjection.Callback sCallback;
        private static Session sSession;

        private MediaProjectionHolder() { }

        public static MediaProjection get() {
            return sProjection;
        }

        static Session getSession() {
            return sSession;
        }

        static void setSession(Session s) {
            sSession = s;
        }

        public static void set(MediaProjection p) {
            sProjection = p;
        }

        public static void clear() {
            sProjection = null;
            sCallback = null;
            sSession = null;
        }

        public static MediaProjectionManager manager(Context ctx) {
            return (MediaProjectionManager) ctx.getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE);
        }

        /**
         * Consume the system consent result and create the projection.
         *
         * getMediaProjection() is valid exactly ONCE per consent token, so this
         * is the single entry point; on failure the user must approve again.
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
            // Android 14+ requires a callback before any capture starts.
            ensureCallback(p);
            sProjection = p;
            return p;
        }

        /**
         * Register the mandatory state callback once per projection. Without it
         * createVirtualDisplay() throws on Android 14+.
         */
        private static void ensureCallback(final MediaProjection p) {
            if (sCallback != null) {
                return;
            }
            sCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.w(TAG, "MediaProjection revoked by the system");
                    closeSession();
                    sProjection = null;
                    sCallback = null;
                }

                @Override
                public void onCapturedContentResize(int width, int height) {
                    // The single VirtualDisplay cannot be recreated for this
                    // projection, so instead of rebuilding we recrop: grab()
                    // trims uniform black bars around the mirrored content.
                    Log.i(TAG, "captured content resized to " + width + "x" + height);
                }

                @Override
                public void onCapturedContentVisibilityChanged(boolean isVisible) {
                    Log.i(TAG, "captured content visible=" + isVisible);
                }
            };
            p.registerCallback(sCallback,
                    new Handler(android.os.Looper.getMainLooper()));
        }
    }
}
