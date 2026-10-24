package com.dsh.subtitlestitch;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Core stitching engine.
 *
 * "字募拼接" = take several screenshots (usually of a film/series, each showing
 * one line of subtitle) and join them vertically into a single long image, so a
 * sequence of dialogue appears in one picture.
 *
 * Each source image may have a "crop window": a top and bottom fraction
 * (0..1) selecting which horizontal band of that image to keep. This lets the
 * user drop the duplicated status bar / bottom nav / repeated header between
 * shots, which is exactly what the original ROM feature's draggable handles did.
 *
 * The engine is pure android.graphics -- no OEM API, no native library -- so it
 * runs on any Android device.
 */
public final class StitchEngine {

    /** Per-image crop window, expressed as fractions of the image height. */
    public static final class CropWindow {
        public float top = 0f;
        public float bottom = 1f;

        public CropWindow() { }

        public CropWindow(float top, float bottom) {
            this.top = clamp01(top);
            this.bottom = clamp01(bottom);
            if (this.bottom <= this.top) {
                this.bottom = Math.min(1f, this.top + 0.05f);
            }
        }

        public CropWindow copy() { return new CropWindow(top, bottom); }

        /** Height in pixels of the kept band, for a given image height. */
        public int keptHeight(int imageHeight) {
            int y0 = (int) (top * imageHeight);
            int y1 = (int) (bottom * imageHeight);
            y1 = Math.min(imageHeight, Math.max(y0 + 1, y1));
            return Math.max(1, y1 - y0);
        }

        /** Top pixel of the kept band, always within [0, height-1]. */
        public int srcTop(int imageHeight) {
            int y0 = (int) (top * imageHeight);
            return Math.max(0, Math.min(imageHeight - 1, y0));
        }

        /** Bottom pixel (exclusive) of the kept band, always > srcTop. */
        public int srcBottom(int imageHeight) {
            int y0 = srcTop(imageHeight);
            int y1 = (int) (bottom * imageHeight);
            return Math.min(imageHeight, Math.max(y0 + 1, y1));
        }

        public Rect srcRect(int imageWidth, int imageHeight) {
            return new Rect(0, srcTop(imageHeight), imageWidth, srcBottom(imageHeight));
        }
    }

    private StitchEngine() { }

    public static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /**
     * Decode an image, downsampled so that its largest dimension is at most
     * maxDim (0 = full size). Subsampling happens at decode time so we do not
     * blow the heap on a 4K screenshot.
     */
    public static Bitmap decodeSampled(String path, int maxDim) {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, probe);
        if (probe.outWidth <= 0 || probe.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        if (maxDim > 0) {
            int longest = Math.max(probe.outWidth, probe.outHeight);
            while (longest / sample > maxDim) {
                sample *= 2;
            }
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            return BitmapFactory.decodeFile(path, opts);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * Render the stitched result.
     *
     * @param paths   source image files, in display order
     * @param crops   one CropWindow per path (same order / size)
     * @param outMax  longest side of the output; every image is scaled so their
     *                widths match the narrowest source, keeping proportions sane
     * @param gap     pixels of vertical gap between shots (0 = flush)
     * @param bg      background colour for gaps / letterboxing
     */
    public static Bitmap render(List<String> paths, List<CropWindow> crops,
                                int outMax, int gap, int bg) {
        int n = paths.size();
        if (n == 0) {
            return null;
        }
        // 1. probe sizes
        int[] ws = new int[n];
        int[] hs = new int[n];
        for (int i = 0; i < n; i++) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(paths.get(i), o);
            ws[i] = Math.max(1, o.outWidth);
            hs[i] = Math.max(1, o.outHeight);
        }

        // 2. output width = the most common (narrowest) width, so nothing is
        //    stretched: scale every band by the same factor.
        int outW = ws[0];
        for (int i = 1; i < n; i++) {
            outW = Math.min(outW, ws[i]);
        }
        if (outMax > 0 && outW > outMax) {
            outW = outMax;
        }
        if (outW < 1) {
            outW = 1;
        }

        // 3. compute per-band scaled heights up-front so we can size the canvas
        CropWindow[] cw = new CropWindow[n];
        int[] bandH = new int[n];
        float[] scale = new float[n];
        long totalH = 0;
        for (int i = 0; i < n; i++) {
            cw[i] = (crops != null && i < crops.size() && crops.get(i) != null)
                    ? crops.get(i) : new CropWindow();
            scale[i] = (float) outW / (float) ws[i];
            int kept = cw[i].keptHeight(hs[i]);
            bandH[i] = Math.max(1, Math.round(kept * scale[i]));
            totalH += bandH[i];
        }
        if (gap > 0 && n > 1) {
            totalH += (long) gap * (n - 1);
        }
        if (totalH < 1) {
            totalH = 1;
        }
        // Guard: a single bitmap cannot exceed the max texture/heap sane size.
        int maxSide = 32000;
        if (totalH > maxSide) {
            float k = maxSide / (float) totalH;
            for (int i = 0; i < n; i++) {
                bandH[i] = Math.max(1, (int) (bandH[i] * k));
            }
            totalH = 0;
            for (int i = 0; i < n; i++) {
                totalH += bandH[i];
            }
            if (gap > 0 && n > 1) {
                totalH += (long) gap * (n - 1);
            }
        }

        Bitmap out;
        try {
            out = Bitmap.createBitmap(Math.max(1, outW), (int) totalH,
                    Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            return null;
        }
        Canvas canvas = new Canvas(out);
        canvas.drawColor(bg);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        paint.setAntiAlias(true);

        int y = 0;
        for (int i = 0; i < n; i++) {
            Bitmap src = decodeSampled(paths.get(i), Math.max(ws[i], hs[i]));
            if (src != null) {
                Rect s = cw[i].srcRect(src.getWidth(), src.getHeight());
                Rect d = new Rect(0, y, outW, y + bandH[i]);
                canvas.drawBitmap(src, s, d, paint);
                src.recycle();
            } else {
                // unreadable source: leave a neutral block rather than fail
                Paint err = new Paint();
                err.setColor(Color.DKGRAY);
                canvas.drawRect(0, y, outW, y + bandH[i], err);
            }
            y += bandH[i];
            if (gap > 0 && i < n - 1) {
                y += gap;
            }
        }
        return out;
    }

    /**
     * Default crop for a NEW screenshot captured into a series.
     *
     * Subtitles in film/TV screenshots are usually near the bottom, and the user
     * explicitly asked for "keep the lower 50%" as the starting point. Starting
     * from a non-edge window also makes the draggable handles visible, so a user
     * who sees a full-height image immediately understands it can be adjusted.
     */
    public static CropWindow defaultLinkedCrop() {
        return new CropWindow(0.5f, 1.0f);
    }

    /** Full frame -- used for the first image of a series. */
    public static CropWindow fullCrop() {
        return new CropWindow(0f, 1f);
    }

    /**
     * Build the crop list for a captured series under the "first full, rest
     * linked" model.
     *
     * Index 0 keeps the whole frame (it usually carries the title / character),
     * every later index shares ONE window so the same band is cut from each
     * subsequent screenshot. That is what makes a sequence of subtitle lines
     * line up without duplicated headers between shots.
     */
    public static List<CropWindow> linkedCrops(int count, CropWindow shared) {
        List<CropWindow> list = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            list.add(i == 0 ? fullCrop() : shared.copy());
        }
        return list;
    }

    /**
     * Estimate a sensible default crop for neighbouring screenshots: keep the
     * full image but trim nothing. (Automatic overlap detection is intentionally
     * out of scope -- the draggable handles let the user decide precisely.)
     */
    public static List<CropWindow> defaultCrops(int count) {
        List<CropWindow> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new CropWindow());
        }
        return list;
    }

    public static String describe(Bitmap b) {
        if (b == null) {
            return "null";
        }
        return b.getWidth() + "x" + b.getHeight();
    }
}
