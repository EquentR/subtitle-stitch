package com.equentr.subtitlestitch;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Device-side regression tests for the real Bitmap-backed StitchEngine.render path. */
public final class FramePreservationTest {
    private static int passed;
    private static int failed;
    private static File testDir;
    private static final List<File> ownedFiles = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: FramePreservationTest <test-temp-directory>");
        }
        testDir = new File(args[0]).getCanonicalFile();
        if (!testDir.isDirectory() && !testDir.mkdirs()) {
            throw new IllegalStateException("cannot create test directory: " + testDir);
        }
        try {
            testBlackBarsArePreserved();
            testUniformDarkAndBlackFramesArePreserved();
            testManualCropIsHonored();
            testLinkedCropsPreserveBars();
            testDownsampleRetainsRequestedScale();
            System.out.println("PASSED=" + passed + " FAILED=" + failed);
        } finally {
            cleanup();
        }
        if (failed != 0) System.exit(1);
    }

    private static void testBlackBarsArePreserved() throws Exception {
        Bitmap source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.BLACK);
        for (int y = 20; y < 80; y++) {
            for (int x = 20; x < 80; x++) source.setPixel(x, y, Color.RED);
        }
        File path = save("bars.png", source);
        source.recycle();
        Bitmap out = render(path, new StitchEngine.CropWindow());
        check("letterbox bars retain full frame dimensions", size(out, 100, 100));
        check("top black bar remains", pixel(out, 50, 5) == Color.BLACK);
        check("bottom black bar remains", pixel(out, 50, 95) == Color.BLACK);
        check("left black sidebar remains", pixel(out, 5, 50) == Color.BLACK);
        check("right black sidebar remains", pixel(out, 95, 50) == Color.BLACK);
        check("interior image content remains", pixel(out, 50, 50) == Color.RED);
        out.recycle();
    }

    private static void testUniformDarkAndBlackFramesArePreserved() throws Exception {
        for (int i = 0; i < 2; i++) {
            int color = i == 0 ? Color.rgb(20, 20, 20) : Color.BLACK;
            Bitmap source = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888);
            source.eraseColor(color);
            File path = save(i == 0 ? "dark.png" : "black.png", source);
            source.recycle();
            Bitmap out = render(path, new StitchEngine.CropWindow());
            check((i == 0 ? "uniform dark" : "all-black") + " frame keeps dimensions", size(out, 48, 64));
            check((i == 0 ? "uniform dark" : "all-black") + " frame keeps pixel content",
                    pixel(out, 24, 32) == color);
            out.recycle();
        }
    }

    private static void testManualCropIsHonored() throws Exception {
        Bitmap source = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) source.setPixel(x, y, Color.RED);
        }
        for (int y = 40; y < 80; y++) {
            for (int x = 0; x < 40; x++) source.setPixel(x, y, Color.BLUE);
        }
        File path = save("manual-crop.png", source);
        source.recycle();
        Bitmap out = render(path, new StitchEngine.CropWindow(0.5f, 1f));
        check("manual lower-half crop controls output height", size(out, 40, 40));
        check("manual crop content starts at selected lower half", pixel(out, 20, 20) == Color.BLUE);
        out.recycle();
    }

    private static void testLinkedCropsPreserveBars() throws Exception {
        File first = saveStriped("linked-first.png", Color.GREEN);
        File second = saveStriped("linked-second.png", Color.YELLOW);
        List<String> paths = Arrays.asList(first.getAbsolutePath(), second.getAbsolutePath());
        List<StitchEngine.CropWindow> crops = StitchEngine.linkedCrops(2,
                new StitchEngine.CropWindow(0.5f, 1f));
        Bitmap out = StitchEngine.render(paths, crops, 0, 0, Color.MAGENTA);
        check("first linked image remains full height", size(out, 60, 180));
        check("first image top black bar remains", pixel(out, 30, 5) == Color.BLACK);
        check("first image lower content remains", pixel(out, 30, 90) == Color.GREEN);
        check("subsequent image uses linked lower-half content", pixel(out, 30, 150) == Color.YELLOW);
        check("subsequent image lower black bar remains", pixel(out, 30, 175) == Color.BLACK);
        out.recycle();
    }

    private static File saveStriped(String name, int content) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(60, 120, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLACK);
        for (int y = 20; y < 100; y++) {
            for (int x = 0; x < 60; x++) bitmap.setPixel(x, y, content);
        }
        File file = save(name, bitmap);
        bitmap.recycle();
        return file;
    }

    private static void testDownsampleRetainsRequestedScale() throws Exception {
        Bitmap source = Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.BLACK);
        for (int y = 100; y < 700; y++) {
            for (int x = 0; x < 360; x++) {
                source.setPixel(x, y, Color.rgb(40 + x % 180, 60 + y % 160, 90));
            }
        }
        File path = save("downsample.png", source);
        source.recycle();
        Bitmap out = StitchEngine.render(Arrays.asList(path.getAbsolutePath()),
                Arrays.asList(new StitchEngine.CropWindow()), 270, 0, Color.BLACK);
        check("360x800 source scales to 270x600 at width cap 270", size(out, 270, 600));
        check("downsampled output contains source image", pixel(out, 135, 300) != Color.BLACK);
        out.recycle();
    }

    private static Bitmap render(File file, StitchEngine.CropWindow crop) {
        return StitchEngine.render(Arrays.asList(file.getAbsolutePath()), Arrays.asList(crop), 0, 0, Color.BLACK);
    }

    private static File save(String name, Bitmap bitmap) throws Exception {
        File file = new File(testDir, "FramePreservationTest-" + name).getCanonicalFile();
        if (!file.getParentFile().equals(testDir)) throw new IllegalStateException("unsafe test path: " + file);
        ownedFiles.add(file);
        FileOutputStream stream = new FileOutputStream(file);
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw new IllegalStateException("PNG encode failed: " + file);
            }
        } finally {
            stream.close();
        }
        return file;
    }

    private static void cleanup() {
        for (File file : ownedFiles) {
            try {
                if (file.getCanonicalFile().getParentFile().equals(testDir)) file.delete();
            } catch (Exception ignored) { }
        }
    }

    private static boolean size(Bitmap bitmap, int width, int height) {
        return bitmap != null && bitmap.getWidth() == width && bitmap.getHeight() == height;
    }

    private static int pixel(Bitmap bitmap, int x, int y) {
        return bitmap == null || x < 0 || y < 0
                || x >= bitmap.getWidth() || y >= bitmap.getHeight()
                ? Integer.MIN_VALUE : bitmap.getPixel(x, y);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }
}
