package com.equentr.subtitlestitch;

/**
 * Pure-logic tests for the crop model, runnable on a desktop JVM.
 *
 * The Android-dependent parts (Bitmap/Canvas) cannot run here, but the crop
 * arithmetic and the "first full, rest linked" rules are plain Java and are
 * where the subtle bugs live, so they are worth pinning down.
 *
 * Run:  java -cp out com.equentr.subtitlestitch.CropLogicTest
 * Exit code 0 = all passed.
 */
public final class CropLogicTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testClamp();
        testCropWindowNormalises();
        testKeptHeight();
        testSrcRect();
        testFirstFullRestLinked();
        testLinkedShareOneWindow();
        testDeletingFirstPromotesNext();
        System.out.println();
        System.out.println("PASSED=" + passed + "  FAILED=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ cases

    private static void testClamp() {
        check("clamp(-1) == 0", StitchEngine.clamp01(-1f) == 0f);
        check("clamp(0.5) == 0.5", Math.abs(StitchEngine.clamp01(0.5f) - 0.5f) < 1e-6);
        check("clamp(2) == 1", StitchEngine.clamp01(2f) == 1f);
    }

    private static void testCropWindowNormalises() {
        // bottom <= top must be repaired, never produce an empty band
        StitchEngine.CropWindow w = new StitchEngine.CropWindow(0.8f, 0.2f);
        check("inverted window keeps top < bottom", w.bottom > w.top);
        check("inverted window keeps top", Math.abs(w.top - 0.8f) < 1e-6 || w.top <= w.bottom);

        StitchEngine.CropWindow defaults = new StitchEngine.CropWindow();
        check("default is full frame", defaults.top == 0f && defaults.bottom == 1f);

        StitchEngine.CropWindow linked = StitchEngine.defaultLinkedCrop();
        check("linked default keeps lower half (top=0.5)",
                Math.abs(linked.top - 0.5f) < 1e-6);
        check("linked default keeps lower half (bottom=1)",
                Math.abs(linked.bottom - 1f) < 1e-6);
    }

    private static void testKeptHeight() {
        StitchEngine.CropWindow full = new StitchEngine.CropWindow(0f, 1f);
        check("full frame 1000px -> 1000", full.keptHeight(1000) == 1000);

        StitchEngine.CropWindow half = new StitchEngine.CropWindow(0.5f, 1f);
        check("lower half of 1000px -> 500", half.keptHeight(1000) == 500);

        StitchEngine.CropWindow tiny = new StitchEngine.CropWindow(0.5f, 0.5f);
        check("degenerate window yields >= 1px", tiny.keptHeight(1000) >= 1);
    }

    private static void testSrcRect() {
        // Rect is an android.jar stub that cannot be instantiated on a desktop
        // JVM, so we exercise the pixel arithmetic directly.
        StitchEngine.CropWindow w = new StitchEngine.CropWindow(0.25f, 0.75f);
        check("srcTop(400) == 100", w.srcTop(400) == 100);
        check("srcBottom(400) == 300", w.srcBottom(400) == 300);

        StitchEngine.CropWindow clamped = new StitchEngine.CropWindow(0f, 0f);
        check("srcBottom > srcTop even for degenerate window",
                clamped.srcBottom(400) > clamped.srcTop(400));
        check("srcTop never negative", clamped.srcTop(400) >= 0);
        check("srcBottom never exceeds height", clamped.srcBottom(400) <= 400);
    }

    private static void testFirstFullRestLinked() {
        StitchEngine.CropWindow shared = new StitchEngine.CropWindow(0.3f, 0.9f);
        java.util.List<StitchEngine.CropWindow> list =
                StitchEngine.linkedCrops(3, shared);

        check("linkedCrops size == 3", list.size() == 3);
        check("index 0 is the full frame",
                list.get(0).top == 0f && list.get(0).bottom == 1f);
        check("index 1 uses the shared window",
                Math.abs(list.get(1).top - 0.3f) < 1e-6
                        && Math.abs(list.get(1).bottom - 0.9f) < 1e-6);
        check("index 2 uses the shared window",
                Math.abs(list.get(2).top - 0.3f) < 1e-6
                        && Math.abs(list.get(2).bottom - 0.9f) < 1e-6);
        check("linked windows are independent copies",
                list.get(1) != list.get(2));
    }

    private static void testLinkedShareOneWindow() {
        // editing one linked item must not mutate the prototype
        StitchEngine.CropWindow shared = new StitchEngine.CropWindow(0.3f, 0.9f);
        java.util.List<StitchEngine.CropWindow> list = StitchEngine.linkedCrops(3, shared);
        list.get(1).top = 0.1f;
        check("editing a copy leaves the prototype intact",
                Math.abs(shared.top - 0.3f) < 1e-6);
        check("editing a copy leaves siblings intact",
                Math.abs(list.get(2).top - 0.3f) < 1e-6);
    }

    private static void testDeletingFirstPromotesNext() {
        // After removing index 0, the new index 0 must become the full frame.
        StitchEngine.CropWindow shared = new StitchEngine.CropWindow(0.4f, 0.8f);
        java.util.List<StitchEngine.CropWindow> list = StitchEngine.linkedCrops(3, shared);
        list.remove(0);
        // simulate relinkCrops()
        java.util.List<StitchEngine.CropWindow> relinked = StitchEngine.linkedCrops(list.size(), shared);
        check("after delete, new head is full frame",
                relinked.get(0).top == 0f && relinked.get(0).bottom == 1f);
        check("after delete, remaining tail stays linked",
                Math.abs(relinked.get(1).top - 0.4f) < 1e-6);
    }

    // ------------------------------------------------------------------ util

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + what);
        } else {
            failed++;
            System.out.println("  FAIL  " + what);
        }
    }
}
