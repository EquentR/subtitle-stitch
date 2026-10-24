package com.dsh.subtitlestitch;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;

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

    /** Reusable row buffer so trimming does not allocate per scan line. */
    private static int[] sRowBuf;

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
        opts.inSampleSize = Math.max(1, sample);
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        // inSampleSize only halves, so the decoded bitmap can be noticeably
        // SMALLER than requested (e.g. 640px for a 1000px target), and the
        // scaling up from there is what made output look soft. Decode to the
        // nearest power of two and let the bitmap factory scale the rest of
        // the way at native quality.
        try {
            Bitmap b = BitmapFactory.decodeFile(path, opts);
            if (b == null) {
                // Retry with a hard downsample: some very large screenshots fail
                // a straightforward decode on low-memory devices.
                opts.inSampleSize = Math.max(2, sample * 2);
                b = BitmapFactory.decodeFile(path, opts);
            }
            return b;
        } catch (OutOfMemoryError e) {
            System.gc();
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
        // 1. probe sizes, accounting for black-bar trimming.
        int[] ws = new int[n];
        int[] hs = new int[n];
        for (int i = 0; i < n; i++) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(paths.get(i), o);
            int ow = Math.max(1, o.outWidth);
            int oh = Math.max(1, o.outHeight);
            // Measure the content box once at low resolution so band heights are
            // computed from the TRIMMED image. Otherwise the layout reserves space
            // for the black bars and the result keeps a big empty margin.
            float[] box = probeContentBox(paths.get(i), ow, oh);
            ws[i] = Math.max(1, Math.round(ow * (box[2] - box[0])));
            hs[i] = Math.max(1, Math.round(oh * (box[3] - box[1])));
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
        // Bilinear + no dither: the classic combination for photographic scaling.
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setAntiAlias(true);
        paint.setAntiAlias(true);

        int y = 0;
        for (int i = 0; i < n; i++) {
            // Decode at the SOURCE resolution. Any downsampling here would be
            // scaled straight back up by drawBitmap and visibly soften the
            // result -- which is exactly what made the stitched image blurrier
            // than the originals. The output width equals the narrowest source
            // width, so full-size decoding needs no up-sampling for that image
            // and only a mild downscale for wider ones.
            Bitmap src = decodeSampled(paths.get(i), Math.max(ws[i], hs[i]));
            if (src == null) {
                // fall back to a coarser sample only if full size fails (OOM)
                src = decodeSampled(paths.get(i), Math.max(ws[i], hs[i]) / 2);
            }
            Bitmap owned = null;
            if (src != null) {
                // Strip uniform black bars HERE rather than only when capturing:
                // this covers screenshots taken by an older build, gallery picks,
                // and captures whose orientation did not match the surface.
                Bitmap trimmed = trimBlackBorders(src);
                if (trimmed != src) {
                    owned = trimmed;
                    src = trimmed;
                }
                // Map the user's crop window onto the TRIMMED content only.
                Rect s = cw[i].srcRect(src.getWidth(), src.getHeight());
                Rect d = new Rect(0, y, outW, y + bandH[i]);
                canvas.drawBitmap(src, s, d, paint);
                if (owned != null) {
                    owned.recycle();
                } else {
                    src.recycle();
                }
            } else {
                Log.w("StitchEngine", "could not decode " + paths.get(i));
            }
            y += bandH[i];
            if (gap > 0 && i < n - 1) {
                y += gap;
            }
        }
        return out;
    }

    /**
     * Remove uniform black bars around the captured content.
     *
     * Returns the same instance when nothing needed trimming (so the caller can
     * tell whether it must recycle the result separately).
     */
    /**
     * Estimate the content rectangle (fractions 0..1) of an image, ignoring
     * uniform black bars. Uses a low-resolution decode: this only needs to be
     * accurate enough to size the output, the real trim happens per band.
     */
    /** Detect the content box of a bitmap: returns {left, top, right, bottom}. */
    public static int[] contentBox(Bitmap src) {
        final int w = src.getWidth();
        final int h = src.getHeight();
        int[] full = new int[]{0, 0, w, h};
        if (w < 16 || h < 16) {
            return full;
        }
        final int threshold = 36;   // JPEG noise / slight glow above a bar
        int top = 0, bottom = h - 1, left = 0, right = w - 1;
        while (top < h / 3 && rowIsBlack(src, w, top, threshold)) top++;
        while (bottom > h * 2 / 3 && rowIsBlack(src, w, bottom, threshold)) bottom--;
        while (left < w / 3 && colIsBlack(src, h, left, threshold)) left++;
        while (right > w * 2 / 3 && colIsBlack(src, h, right, threshold)) right--;
        int nw = right - left + 1;
        int nh = bottom - top + 1;
        if (nw < w / 2 || nh < h / 4) {
            return full;             // implausible, keep everything
        }
        return new int[]{left, top, left + nw, top + nh};
    }

    /**
     * Per-row blackness using a bulk pixel read.
     *
     * Bitmap.getPixel() in a loop is very slow for a 1264x2780 screenshot (we
     * measured it dominating the whole capture). getPixels() copies a whole row
     * in one JNI call instead.
     */
    private static boolean rowIsBlack(Bitmap b, int w, int y, int t) {
        int[] row = sRowBuf;
        if (row == null || row.length < w) {
            row = new int[w];
            sRowBuf = row;
        }
        b.getPixels(row, 0, w, 0, y, w, 1);
        for (int x = 0; x < w; x += 4) {
            int c = row[x];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, bl = c & 0xFF;
            if (r > t || g > t || bl > t) {
                return false;
            }
        }
        return true;
    }

    private static boolean colIsBlack(Bitmap b, int h, int x, int t) {
        // Column scans are rare (only when the image is letterboxed sideways), so
        // a modest sampling step keeps them cheap without getPixel() per pixel.
        int step = Math.max(1, h / 64);
        for (int y = 0; y < h; y += step) {
            int c = b.getPixel(x, y);
            if (((c >> 16) & 0xFF) > t || ((c >> 8) & 0xFF) > t || (c & 0xFF) > t) {
                return false;
            }
        }
        return true;
    }

    /**
     * Crop a bitmap to the given fractional content box.
     *
     * Cropping by FRACTION (not by absolute pixels) guarantees the grid preview
     * and the zoom editor produce identical framing even though they are decoded
     * at different resolutions.
     */
    public static Bitmap cropToFractions(Bitmap src, float[] box) {
        if (src == null || box == null) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int l = Math.round(box[0] * w);
        int t = Math.round(box[1] * h);
        int r = Math.round(box[2] * w);
        int b = Math.round(box[3] * h);
        l = Math.max(0, Math.min(w - 1, l));
        t = Math.max(0, Math.min(h - 1, t));
        r = Math.max(l + 1, Math.min(w, r));
        b = Math.max(t + 1, Math.min(h, b));
        if (l == 0 && t == 0 && r == w && b == h) {
            return src;                 // nothing to do
        }
        return Bitmap.createBitmap(src, l, t, r - l, b - t);
    }

    /**
     * Content rectangle of an image as fractions {l, t, r, b}, measured on a
     * small decode. Used while planning the layout so band heights are based on
     * the trimmed image rather than including black bars.
     */
    public static float[] probeContentBox(String path, int w, int h) {
        float[] full = new float[]{0f, 0f, 1f, 1f};
        if (w < 32 || h < 32) {
            return full;
        }
        Bitmap small = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = Math.max(1, Math.max(w, h) / 256);
            small = BitmapFactory.decodeFile(path, o);
            if (small == null) {
                return full;
            }
            int[] b = contentBox(small);
            if (b[0] == 0 && b[1] == 0
                    && b[2] == small.getWidth() && b[3] == small.getHeight()) {
                return full;
            }
            return new float[]{
                    b[0] / (float) small.getWidth(),
                    b[1] / (float) small.getHeight(),
                    b[2] / (float) small.getWidth(),
                    b[3] / (float) small.getHeight()};
        } catch (Exception e) {
            return full;
        } finally {
            if (small != null && !small.isRecycled()) {
                small.recycle();
            }
        }
    }

    public static Bitmap trimBlackBorders(Bitmap src) {
        final int w = src.getWidth();
        final int h = src.getHeight();
        if (w < 16 || h < 16) {
            return src;
        }
        final int threshold = 36;   // JPEG noise / slight glow above a bar      // treat near-black as bar
        int top = 0, bottom = h - 1, left = 0, right = w - 1;

        java.util.function.IntPredicate rowIsBlack = y -> {
            for (int x = 0; x < w; x += 7) {
                int c = src.getPixel(x, y);
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                if (r > threshold || g > threshold || b > threshold) {
                    return false;
                }
            }
            return true;
        };
        java.util.function.IntPredicate colIsBlack = x -> {
            for (int y = 0; y < h; y += 7) {
                int c = src.getPixel(x, y);
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                if (r > threshold || g > threshold || b > threshold) {
                    return false;
                }
            }
            return true;
        };

        while (top < h / 3 && rowIsBlack.test(top)) top++;
        while (bottom > h * 2 / 3 && rowIsBlack.test(bottom)) bottom--;
        while (left < w / 3 && colIsBlack.test(left)) left++;
        while (right > w * 2 / 3 && colIsBlack.test(right)) right--;

        if (top == 0 && left == 0 && bottom == h - 1 && right == w - 1) {
            return src;
        }
        int nw = right - left + 1;
        int nh = bottom - top + 1;
        if (nw < w / 2 || nh < h / 4) {
            return src;   // implausible result, keep the original
        }
        android.util.Log.i("StitchEngine", "trimmed black bars: " + w + "x" + h + " -> " + nw + "x" + nh);
        return Bitmap.createBitmap(src, left, top, nw, nh);
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
