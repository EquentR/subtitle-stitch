package com.equentr.subtitlestitch;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/** Full-screen capture through one VirtualDisplay for each projection consent. */
public final class ScreenCapture {
    private static final String TAG = "SubtitleStitch";

    private ScreenCapture() { }

    public static final class NoConsentException extends Exception {
        public NoConsentException() { super("media projection consent required"); }
    }

    /** Screenshots need the entire display, rather than a selected app window. */
    public static Intent createConsentIntent(Context context) {
        MediaProjectionManager manager = MediaProjectionHolder.manager(context);
        if (manager == null) {
            throw new IllegalStateException("MediaProjectionManager unavailable");
        }
        if (Build.VERSION.SDK_INT >= 34) {
            return manager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay());
        }
        return manager.createScreenCaptureIntent();
    }

    /**
     * Owns a single VirtualDisplay; resizing replaces only its receiving surface.
     * Reader lifetime and frame copying share a lock, so a resize cannot close a
     * reader while another thread is copying its image. Encoding never holds it.
     */
    public static final class Session {
        private final Context context;
        private final MediaProjection projection;
        private final VirtualDisplay display;
        private final HandlerThread thread;
        private final Handler handler;
        private final DisplayManager displayManager;
        private final Object frameLock = new Object();
        private final int density;
        private ImageReader reader;
        private int width;
        private int height;
        private int requestedWidth;
        private int requestedHeight;
        private int requestedRotation;
        private int observedRotation;
        private long geometryReadyAt;
        private long requestedGeneration;
        private long appliedGeneration;
        private boolean contentSizeKnown;
        private boolean resizeQueued;
        private volatile boolean closed;

        private final DisplayManager.DisplayListener displayListener =
                new DisplayManager.DisplayListener() {
                    @Override public void onDisplayAdded(int id) { }
                    @Override public void onDisplayRemoved(int id) { }
                    @Override public void onDisplayChanged(int id) {
                        if (id == Display.DEFAULT_DISPLAY) {
                            synchronizeDisplaySize();
                        }
                    }
                };

        private Session(Context context, MediaProjection projection, ImageReader reader,
                        VirtualDisplay display, HandlerThread thread, int width,
                        int height, int density) {
            this.context = context.getApplicationContext();
            this.projection = projection;
            this.reader = reader;
            this.display = display;
            this.thread = thread;
            this.handler = new Handler(thread.getLooper());
            this.displayManager = (DisplayManager) this.context.getSystemService(
                    Context.DISPLAY_SERVICE);
            this.width = this.requestedWidth = width;
            this.height = this.requestedHeight = height;
            this.density = density;
            this.requestedRotation = this.observedRotation = currentRotation();
            this.geometryReadyAt = SystemClock.uptimeMillis();
        }

        public int width() { synchronized (frameLock) { return width; } }
        public int height() { synchronized (frameLock) { return height; } }
        public boolean isAlive() { return !closed; }

        private int currentRotation() {
            Display source = displayManager == null ? null
                    : displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            return source == null ? 0 : source.getRotation();
        }

        private void startMonitoring() {
            if (displayManager != null) {
                displayManager.registerDisplayListener(displayListener, handler);
            }
            synchronizeDisplaySize();
        }

        private static final long FRAME_DEADLINE_MS = 2200;
        // A geometry change begins system compositor animation; ordinary captures
        // keep the creation-time deadline and do not pay this transition wait.
        private static final long ROTATION_SETTLE_BASE_MS = 200;
        private static final long ROTATION_SETTLE_SCALE_MS = 300;
        private boolean isSettled() {
            return SystemClock.uptimeMillis() >= geometryReadyAt;
        }

        private long rotationSettleDelayMs() {
            float scale = 1f;
            try {
                float transition = Settings.Global.getFloat(context.getContentResolver(),
                        Settings.Global.TRANSITION_ANIMATION_SCALE, 1f);
                float window = Settings.Global.getFloat(context.getContentResolver(),
                        Settings.Global.WINDOW_ANIMATION_SCALE, 1f);
                scale = Math.max(transition, window);
            } catch (RuntimeException ignored) { }
            scale = Math.max(0f, Math.min(5f, scale));
            return ROTATION_SETTLE_BASE_MS + (long) (ROTATION_SETTLE_SCALE_MS * scale);
        }

        private void synchronizeDisplaySize() {
            synchronized (frameLock) {
                // On Android 14+, captured-content callbacks are authoritative.
                // Do not fight those dimensions with a stale display/configuration.
                if (closed || (Build.VERSION.SDK_INT >= 34 && contentSizeKnown)) {
                    return;
                }
            }
            int[] size = currentScreenSize(context);
            requestSize(size[0], size[1], false);
        }

        private void requestSize(int newWidth, int newHeight, boolean fromContent) {
            if (newWidth <= 0 || newHeight <= 0) {
                return;
            }
            synchronized (frameLock) {
                if (closed) {
                    return;
                }
                if (fromContent) {
                    contentSizeKnown = true;
                } else if (Build.VERSION.SDK_INT >= 34 && contentSizeKnown) {
                    return;
                }
                int rotation = currentRotation();
                boolean directionChanged = rotation != observedRotation;
                boolean sizeChanged = newWidth != requestedWidth || newHeight != requestedHeight;
                observedRotation = rotation;
                if (!sizeChanged && !directionChanged) {
                    return;
                }
                requestedWidth = newWidth;
                requestedHeight = newHeight;
                requestedRotation = rotation;
                requestedGeneration++;
                geometryReadyAt = SystemClock.uptimeMillis() + rotationSettleDelayMs();
                if (!resizeQueued) {
                    resizeQueued = true;
                    handler.post(this::applyResize);
                }
            }
        }

        private void applyResize() {
            synchronized (frameLock) {
                resizeQueued = false;
                if (closed || appliedGeneration == requestedGeneration) {
                    return;
                }
                ImageReader replacement = null;
                try {
                    if (width != requestedWidth || height != requestedHeight) {
                        replacement = ImageReader.newInstance(requestedWidth, requestedHeight,
                                PixelFormat.RGBA_8888, 2);
                        // Android 14 permits resizing this display, but not creating
                        // a second VirtualDisplay with the same projection token.
                        display.setSurface(null);
                        display.resize(requestedWidth, requestedHeight, density);
                        display.setSurface(replacement.getSurface());
                        ImageReader previous = reader;
                        reader = replacement;
                        replacement = null;
                        width = requestedWidth;
                        height = requestedHeight;
                        previous.close();
                    }
                    appliedGeneration = requestedGeneration;
                    Log.i(TAG, "capture surface resized " + width + "x" + height
                            + " generation=" + appliedGeneration);
                } catch (RuntimeException error) {
                    if (replacement != null) {
                        replacement.close();
                    }
                    Log.w(TAG, "cannot resize capture surface", error);
                    shutdown();
                }
            }
        }

        private boolean geometryIsCurrent() {
            // A 90-degree rotation can be visible to Display before the projection
            // callback arrives. Wait rather than save a frame on the old surface.
            return !closed && requestedGeneration == appliedGeneration
                    && requestedRotation % 2 == currentRotation() % 2;
        }

        /** Copy a fresh full frame; the bubble only needs hiding until this returns. */
        public Bitmap grabFrame() {
            if (closed) {
                return null;
            }
            synchronizeDisplaySize();
            long start = SystemClock.elapsedRealtime();
            long drainedGeneration = -1;
            try {
            while (!closed && SystemClock.elapsedRealtime() - start < FRAME_DEADLINE_MS) {
                    synchronized (frameLock) {
                        if (requestedGeneration == appliedGeneration && !closed) {
                            Image image = reader.acquireLatestImage();
                            if (drainedGeneration != appliedGeneration) {
                                // Drop anything queued before the overlay was hidden,
                                // and repeat this drain if the receiving surface changes.
                                if (image != null) image.close();
                                drainedGeneration = appliedGeneration;
                            } else if (image != null) {
                                try {
                                    if (geometryIsCurrent() && isSettled()
                                            && image.getWidth() == width
                                            && image.getHeight() == height) {
                                        Bitmap bitmap = toBitmap(image);
                                        if (geometryIsCurrent() && isSettled()) {
                                            Log.i(TAG, "captured full frame " + width + "x" + height
                                                    + " generation=" + appliedGeneration
                                                    + " elapsed=" + (SystemClock.elapsedRealtime() - start)
                                                    + "ms");
                                            return bitmap;
                                        }
                                        bitmap.recycle();
                                    }
                                } finally {
                                    image.close();
                                }
                            }
                        }
                    }
                    Thread.sleep(8);
                }
                Log.w(TAG, "grabFrame: no settled full-size frame within "
                        + FRAME_DEADLINE_MS + "ms");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException error) {
                Log.w(TAG, "grabFrame failed", error);
            }
            return null;
        }

        /** Encode the complete frame after the overlay can safely reappear. */
        public File encodeFile(Context context, Bitmap bitmap) {
            if (bitmap == null) {
                return null;
            }
            long start = SystemClock.elapsedRealtime();
            try {
                File output = saveJpeg(context, bitmap);
                Log.i(TAG, "encoded full frame " + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " elapsed=" + (SystemClock.elapsedRealtime() - start) + "ms");
                return output;
            } finally {
                bitmap.recycle();
            }
        }

        public File grab(Context context) {
            return encodeFile(context, grabFrame());
        }

        void shutdown() {
            synchronized (frameLock) {
                if (closed) {
                    return;
                }
                closed = true;
                if (displayManager != null) {
                    displayManager.unregisterDisplayListener(displayListener);
                }
                handler.removeCallbacksAndMessages(null);
                try { display.release(); } catch (RuntimeException ignored) { }
                try { reader.close(); } catch (RuntimeException ignored) { }
                thread.quitSafely();
            }
        }
    }

    /** Current logical full-display bounds, already rotated by the display system. */
    public static int[] currentScreenSize(Context context) {
        DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display source = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
        if (source != null) {
            Point size = new Point();
            source.getRealSize(size);
            if (size.x > 0 && size.y > 0) {
                return new int[] {size.x, size.y};
            }
        }
        WindowManager windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windows != null && Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect bounds = windows.getMaximumWindowMetrics().getBounds();
            return new int[] {bounds.width(), bounds.height()};
        }
        throw new IllegalStateException("Full display dimensions unavailable");
    }

    /** Called once per consent; orientation changes resize this same display. */
    public static Session openSession(Context context, MediaProjection projection)
            throws NoConsentException {
        if (projection == null) {
            throw new NoConsentException();
        }
        int[] size = currentScreenSize(context);
        int density = context.getResources().getDisplayMetrics().densityDpi;
        HandlerThread thread = new HandlerThread("capture");
        thread.start();
        ImageReader reader = null;
        VirtualDisplay display = null;
        try {
            reader = ImageReader.newInstance(size[0], size[1], PixelFormat.RGBA_8888, 2);
            display = projection.createVirtualDisplay("stitch-capture", size[0], size[1], density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(),
                    null, new Handler(thread.getLooper()));
            if (display == null) {
                throw new IllegalStateException("VirtualDisplay unavailable");
            }
            Session session = new Session(context, projection, reader, display, thread,
                    size[0], size[1], density);
            MediaProjectionHolder.setSession(session);
            session.startMonitoring();
            Log.i(TAG, "capture session opened " + size[0] + "x" + size[1]);
            return session;
        } catch (RuntimeException error) {
            if (display != null) display.release();
            if (reader != null) reader.close();
            thread.quitSafely();
            MediaProjectionHolder.setSession(null);
            throw error;
        }
    }

    public static void closeSession() {
        Session session = MediaProjectionHolder.getSession();
        if (session != null) {
            session.shutdown();
            MediaProjectionHolder.setSession(null);
            Log.i(TAG, "capture session closed");
        }
    }

    public static boolean hasConsent(Context context) {
        Session session = MediaProjectionHolder.getSession();
        return session != null && session.isAlive();
    }

    private static Bitmap toBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        int bitmapWidth = image.getWidth() + rowPadding / pixelStride;
        Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        if (bitmapWidth == image.getWidth()) {
            return bitmap;
        }
        // This removes buffer alignment padding, never pixels classified by color.
        Bitmap output = Bitmap.createBitmap(bitmap, 0, 0, image.getWidth(), image.getHeight());
        bitmap.recycle();
        return output;
    }

    private static File saveJpeg(Context context, Bitmap bitmap) {
        File directory = new File(context.getCacheDir(), "captured");
        if (!directory.exists()) directory.mkdirs();
        File output = new File(directory, "shot_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream);
            return output;
        } catch (Exception error) {
            Log.w(TAG, "saveJpeg failed", error);
            return null;
        }
    }

    public static final class MediaProjectionHolder {
        private static volatile MediaProjection sProjection;
        private static MediaProjection sCallbackOwner;
        private static MediaProjection.Callback sCallback;
        private static volatile Session sSession;

        private MediaProjectionHolder() { }
        public static MediaProjection get() { return sProjection; }
        static Session getSession() { return sSession; }
        static void setSession(Session session) { sSession = session; }
        public static void set(MediaProjection projection) { sProjection = projection; }
        public static void clear() {
            sProjection = null;
            sCallbackOwner = null;
            sCallback = null;
            sSession = null;
        }
        public static MediaProjectionManager manager(Context context) {
            return (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        }
        public static MediaProjection create(Context context, int resultCode, Intent data) {
            MediaProjectionManager manager = manager(context);
            if (manager == null) throw new IllegalStateException("MediaProjectionManager unavailable");
            MediaProjection projection = manager.getMediaProjection(resultCode, data);
            if (projection == null) throw new IllegalStateException("getMediaProjection returned null");
            sProjection = projection;
            ensureCallback(projection);
            return projection;
        }
        private static void ensureCallback(final MediaProjection projection) {
            if (sCallbackOwner == projection) return;
            sCallback = new MediaProjection.Callback() {
                @Override public void onStop() {
                    // A delayed stop from an old consent must not close a newer session.
                    if (sProjection != projection) return;
                    Log.w(TAG, "MediaProjection revoked by the system");
                    closeSession();
                    sProjection = null;
                    sCallbackOwner = null;
                    sCallback = null;
                }
                @Override public void onCapturedContentResize(int width, int height) {
                    Session session = sSession;
                    if (session != null && session.projection == projection) {
                        session.requestSize(width, height, true);
                    }
                    Log.i(TAG, "captured content resized to " + width + "x" + height);
                }
                @Override public void onCapturedContentVisibilityChanged(boolean visible) {
                    Log.i(TAG, "captured content visible=" + visible);
                }
            };
            projection.registerCallback(sCallback, new Handler(android.os.Looper.getMainLooper()));
            sCallbackOwner = projection;
        }
    }
}
