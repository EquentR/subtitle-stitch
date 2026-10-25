package com.equentr.subtitlestitch;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Temporary end-to-end capture rotation test; included only by the test APK manifest. */
public final class CaptureRotationTestActivity extends Activity {
    private static final String TAG = "CaptureRotationTest";
    private static final int REQUEST_PROJECTION = 7001;
    private static final int[] ROTATIONS = {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private AnimatedCaptureView captureView;
    private Thread worker;
    private volatile boolean workerDone;
    private volatile boolean finished;
    private volatile boolean destroyed;
    private boolean cleaned;
    private int originalOrientation;
    private android.media.projection.MediaProjection projection;
    private ScreenCapture.Session session;
    private ScreenCapture.Session initialSession;
    private final List<String> results = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        originalOrientation = getRequestedOrientation();
        captureView = new AnimatedCaptureView();
        setContentView(captureView);
        clearResultsFile();
        try {
            startActivityForResult(ScreenCapture.createConsentIntent(this), REQUEST_PROJECTION);
        } catch (Throwable error) {
            finishWithFailure("consent launch failed: " + error);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (destroyed) return;
        if (request != REQUEST_PROJECTION) return;
        if (result != RESULT_OK || data == null) {
            finishWithFailure("consent denied");
            return;
        }
        try {
            ShotWatcherService.start(this);
            ShotWatcherService.prepareProjection(this);
        } catch (Throwable error) {
            finishWithFailure("service promotion failed: " + error);
            return;
        }
        main.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    projection = ScreenCapture.MediaProjectionHolder.create(
                            getApplicationContext(), result, data);
                    session = ScreenCapture.openSession(getApplicationContext(), projection);
                    initialSession = session;
                    startSequence();
                } catch (Throwable error) {
                    finishWithFailure("session open failed: " + error);
                }
            }
        }, 400);
    }

    private void startSequence() {
        worker = new Thread(new Runnable() {
            @Override public void run() {
                runCaptures();
            }
        }, "capture-rotation-test");
        worker.start();
    }

    private void runCaptures() {
        try {
            for (int i = 0; i < ROTATIONS.length; i++) {
                final CountDownLatch ready = new CountDownLatch(1);
                final AtomicBoolean geometryReady = new AtomicBoolean(false);
                final int requested = ROTATIONS[i];
                main.post(new Runnable() {
                    @Override public void run() {
                        requestAndWaitForGeometry(requested, ready, geometryReady);
                    }
                });
                if (!ready.await(5, TimeUnit.SECONDS) || !geometryReady.get()) {
                    record(i + " " + orientationName(requested)
                            + " FAIL orientation timeout raw=0x0 jpeg=false(0x0)"
                            + " bars=false fillsWidth=false render=false sameSession=false");
                    return;
                }
                captureOne(i, requested);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            record("FAIL worker interrupted");
        } catch (Throwable error) {
            record("FAIL worker exception: " + error);
        } finally {
            workerDone = true;
            main.post(new Runnable() {
                @Override public void run() {
                    finishRun();
                }
            });
        }
    }

    private void requestAndWaitForGeometry(final int requested, final CountDownLatch ready,
                                           final AtomicBoolean geometryReady) {
        setRequestedOrientation(requested);
        final long deadline = android.os.SystemClock.uptimeMillis() + 5000;
        main.post(new Runnable() {
            @Override public void run() {
                if (geometryMatches(requested)) {
                    geometryReady.set(true);
                    ready.countDown();
                } else if (android.os.SystemClock.uptimeMillis() < deadline) {
                    main.postDelayed(this, 100);
                } else {
                    ready.countDown();
                }
            }
        });
    }

    private boolean geometryMatches(int requested) {
        if (captureView.getWidth() <= 0 || captureView.getHeight() <= 0) return false;
        int[] size;
        try {
            size = ScreenCapture.currentScreenSize(this);
        } catch (Throwable error) {
            return false;
        }
        boolean wantedLandscape = requested == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        boolean displayLandscape = size[0] > size[1];
        boolean viewLandscape = captureView.getWidth() > captureView.getHeight();
        return wantedLandscape == displayLandscape && wantedLandscape == viewLandscape
                && session != null && session.isAlive();
    }

    private void captureOne(int index, int requested) {
        int[] expected = ScreenCapture.currentScreenSize(getApplicationContext());
        Bitmap raw = session.grabFrame();
        if (raw == null) {
            record(index + " " + orientationName(requested)
                    + " FAIL raw=null raw=0x0 jpeg=false(0x0)"
                    + " bars=false fillsWidth=false render=false sameSession=false");
            return;
        }
        int[] location = new int[2];
        captureView.getLocationOnScreen(location);
        boolean bars = blackBarsRemain(raw, location);
        boolean fillsWidth = contentFillsWidth(raw, location);
        boolean rawSize = raw.getWidth() == expected[0] && raw.getHeight() == expected[1];
        int rawWidth = raw.getWidth();
        int rawHeight = raw.getHeight();
        File jpeg = session.encodeFile(getApplicationContext(), raw);
        int[] jpegSize = jpegDimensions(jpeg);
        boolean jpegOk = jpegSize[0] == expected[0] && jpegSize[1] == expected[1];
        boolean renderOk = renderMatches(jpeg, expected);
        boolean sameSession = session == initialSession && session != null && session.isAlive();
        String line = index + " " + orientationName(requested)
                + " raw=" + rawSize + "(" + rawWidth + "x" + rawHeight + ")"
                + " jpeg=" + jpegOk + "(" + jpegSize[0] + "x" + jpegSize[1] + ")"
                + " bars=" + bars + " fillsWidth=" + fillsWidth + " render=" + renderOk
                + " sameSession=" + sameSession;
        record(line);
    }

    private boolean blackBarsRemain(Bitmap bitmap, int[] location) {
        int x = Math.max(0, Math.min(bitmap.getWidth() - 1,
                location[0] + captureView.getWidth() / 2));
        int top = Math.max(0, Math.min(bitmap.getHeight() - 1, location[1] + 12));
        int bottom = Math.max(0, Math.min(bitmap.getHeight() - 1,
                location[1] + captureView.getHeight() - 12));
        return isBlack(bitmap.getPixel(x, top)) && isBlack(bitmap.getPixel(x, bottom));
    }

    private boolean contentFillsWidth(Bitmap bitmap, int[] location) {
        int y = Math.max(0, Math.min(bitmap.getHeight() - 1,
                location[1] + captureView.getHeight() / 2));
        int left = Math.max(0, Math.min(bitmap.getWidth() - 1, location[0] + 4));
        int right = Math.max(0, Math.min(bitmap.getWidth() - 1,
                location[0] + captureView.getWidth() - 5));
        return !isBlack(bitmap.getPixel(left, y)) && !isBlack(bitmap.getPixel(right, y));
    }

    private boolean isBlack(int color) {
        return Color.red(color) <= 12 && Color.green(color) <= 12 && Color.blue(color) <= 12;
    }

    private int[] jpegDimensions(File jpeg) {
        if (jpeg == null) return new int[] {0, 0};
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(jpeg.getAbsolutePath(), options);
        return new int[] {options.outWidth, options.outHeight};
    }

    private boolean renderMatches(File jpeg, int[] expected) {
        if (jpeg == null) return false;
        Bitmap rendered = StitchEngine.render(
                Collections.singletonList(jpeg.getAbsolutePath()),
                Collections.singletonList(StitchEngine.fullCrop()), 0, 0, Color.BLACK);
        if (rendered == null) return false;
        boolean matches = rendered.getWidth() == expected[0]
                && rendered.getHeight() == expected[1];
        rendered.recycle();
        return matches;
    }

    private synchronized void record(String line) {
        results.add(line);
        Log.i(TAG, line);
        appendResult(line);
    }

    private void clearResultsFile() {
        try (FileWriter writer = new FileWriter(
                new File(getFilesDir(), "capture-test-results.txt"), false)) {
            writer.write("");
        } catch (IOException error) {
            Log.w(TAG, "cannot reset results", error);
        }
    }

    private void appendResult(String line) {
        File output = new File(getFilesDir(), "capture-test-results.txt");
        try (FileWriter writer = new FileWriter(output, true)) {
            writer.write(line);
            writer.write('\n');
        } catch (IOException error) {
            Log.w(TAG, "cannot write results", error);
        }
    }

    private void finishRun() {
        if (finished || !workerDone) return;
        finished = true;
        boolean pass = results.size() == ROTATIONS.length;
        for (String result : results) pass &= result.indexOf("FAIL") < 0
                && result.indexOf("raw=true") >= 0
                && result.indexOf("jpeg=true") >= 0
                && result.indexOf("bars=true") >= 0
                && result.indexOf("fillsWidth=true") >= 0
                && result.indexOf("render=true") >= 0
                && result.indexOf("sameSession=true") >= 0;
        String summary = pass ? "PASS" : "FAIL";
        Log.i(TAG, summary);
        appendResult(summary);
        if (destroyed) {
            cleanupProjection();
            return;
        }
        TextView text = new TextView(this);
        text.setText(summary + "\n" + joinResults());
        text.setTextColor(Color.WHITE);
        text.setBackgroundColor(Color.DKGRAY);
        text.setPadding(24, 24, 24, 24);
        setContentView(text);
        cleanupProjection();
    }

    private void cleanupProjection() {
        if (cleaned) return;
        cleaned = true;
        ScreenCapture.closeSession();
        if (projection != null) projection.stop();
        ScreenCapture.MediaProjectionHolder.clear();
        ShotWatcherService.stop(this);
        if (!destroyed) setRequestedOrientation(originalOrientation);
    }

    private String joinResults() {
        StringBuilder text = new StringBuilder();
        for (String result : results) text.append(result).append('\n');
        return text.toString();
    }

    private void finishWithFailure(String message) {
        if (destroyed) return;
        record("FAIL " + message);
        workerDone = true;
        main.post(new Runnable() {
            @Override public void run() { finishRun(); }
        });
    }

    private String orientationName(int orientation) {
        return orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                ? "landscape" : "portrait";
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (worker != null && !workerDone) {
            worker.interrupt();
        } else {
            cleanupProjection();
        }
        super.onDestroy();
    }

    private final class AnimatedCaptureView extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint();
        private float phase;

        AnimatedCaptureView() {
            super(CaptureRotationTestActivity.this);
            paint.setAntiAlias(true);
            setBackgroundColor(Color.BLACK);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int bar = Math.max(48, getHeight() / 6);
            paint.setColor(Color.BLACK);
            canvas.drawRect(0, 0, getWidth(), bar, paint);
            canvas.drawRect(0, getHeight() - bar, getWidth(), getHeight(), paint);
            paint.setColor(Color.rgb(30, 150, 210));
            canvas.drawRect(0, bar, getWidth(), getHeight() - bar, paint);
            paint.setColor(Color.rgb(245, 190, 40));
            float x = (float) ((Math.sin(phase) + 1.0) * 0.5 * getWidth());
            canvas.drawCircle(x, bar + (getHeight() - 2 * bar) / 2f, 24, paint);
            phase += 0.08f;
            postInvalidateDelayed(33);
        }
    }
}
