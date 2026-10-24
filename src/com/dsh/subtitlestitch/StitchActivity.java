package com.dsh.subtitlestitch;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The stitch editor.
 *
 * Left: a scrollable vertical preview of all selected shots, joined the same way
 * the final image will be. Each shot carries a draggable crop window (two handles)
 * so the user can trim status bars, page headers or repeated lines before
 * joining.
 *
 * Right/bottom: controls to reorder, delete, adjust gap, and export.
 */
public class StitchActivity extends Activity {

    public static final String EXTRA_FROM_CAPTURED = "from_captured";

    /** Smallest allowed crop band, as a fraction of image height. */
    private static final float MIN_BAND = 0.02f;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<>();

    private LinearLayout listLayout;
    private TextView emptyHint;
    private Button exportBtn;
    private HorizontalScrollView strip;

    /** One source image plus its crop window and UI handles. */
    private static final class Item {
        String path;
        String label;
        Bitmap bitmap;           // may be downsampled for preview
        StitchEngine.CropWindow crop = new StitchEngine.CropWindow();
        ItemView view;
        /** true for index>0 when the series uses linked cropping */
        boolean linked = false;
    }

    /**
     * The shared window used by every linked (index>0) item. Editing any linked
     * item writes here and is pushed to its siblings, so all subsequent shots
     * keep the same band.
     */
    private StitchEngine.CropWindow sharedCrop = StitchEngine.defaultLinkedCrop();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean fromCaptured = getIntent().getBooleanExtra(EXTRA_FROM_CAPTURED, true);
        setContentView(buildUi());
        if (fromCaptured) {
            loadCaptured();
        } else {
            pickImages();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 100 && res == RESULT_OK && data != null) {
            ArrayList<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                for (int i = 0; i < n; i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            addUris(uris);
        }
    }

    // ------------------------------------------------------------------ UI

    private int dp(float v) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#111418"));

        // top bar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        bar.setBackgroundColor(Color.parseColor("#1A1F26"));

        Button back = small("返回");
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView t = new TextView(this);
        t.setText("字幕拼接");
        t.setTextColor(Color.WHITE);
        t.setTextSize(17);
        t.setPadding(dp(12), 0, 0, 0);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(t, tlp);

        Button add = small("添加");
        add.setOnClickListener(v -> pickImages());
        bar.addView(add);

        exportBtn = small("导出");
        exportBtn.setOnClickListener(v -> export());
        bar.addView(exportBtn);
        root.addView(bar);

        // Respect system bars: pad the top bar by the status bar height and the
        // list by the navigation bar height, so nothing sits under the system UI.
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            bar.setOnApplyWindowInsetsListener((v, insets) -> {
                int top;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    top = insets.getInsets(android.view.WindowInsets.Type.statusBars()
                            | android.view.WindowInsets.Type.displayCutout()).top;
                } else {
                    top = insets.getSystemWindowInsetTop();
                }
                v.setPadding(dp(8), dp(8) + top, dp(8), dp(8));
                return insets;
            });
        }

        // preview list
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        listLayout.setPadding(dp(10), dp(10), dp(10), dp(10));
        sc.addView(listLayout);

        emptyHint = new TextView(this);
        emptyHint.setText("还没有图片。点「添加」从相册选择，或先开启自动捕获截图。");
        emptyHint.setTextColor(Color.parseColor("#8A94A6"));
        emptyHint.setTextSize(14);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setPadding(dp(24), dp(60), dp(24), dp(60));
        listLayout.addView(emptyHint);

        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            sc.setOnApplyWindowInsetsListener((v, insets) -> {
                int bottom;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    bottom = insets.getInsets(
                            android.view.WindowInsets.Type.navigationBars()).bottom;
                } else {
                    bottom = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(0, 0, 0, bottom);
                return insets;
            });
        }
        return root;
    }

    private Button small(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor("#2A3038"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        return b;
    }

    // --------------------------------------------------------------- loading

    private void loadCaptured() {
        List<MediaStoreUtil.Shot> shots = ShotWatcherService.captured();
        if (shots.isEmpty()) {
            toast("还没有捕获到截图，先开启自动捕获，或点「添加」手动选图");
            return;
        }
        // Drop entries whose gallery item was deleted: keeping them would open
        // the editor as a batch of placeholder failures.
        List<MediaStoreUtil.Shot> alive = new ArrayList<>();
        int dead = 0;
        for (MediaStoreUtil.Shot s : shots) {
            if (s.localPath != null || imageExists(s.uri)) {
                alive.add(s);
            } else {
                dead++;
            }
        }
        if (dead > 0) {
            ShotWatcherService.setCaptured(alive);
            ShotStore.save(this, alive);
            toast("已忽略 " + dead + " 张已被删除的截图");
        }
        if (alive.isEmpty()) {
            toast("已捕获的截图都已失效，请重新截屏或手动选图");
            return;
        }
        List<Uri> uris = new ArrayList<>();
        for (MediaStoreUtil.Shot s : alive) {
            uris.add(s.uri);
        }
        addUris(uris);
    }

    /** True if this MediaStore row still resolves to readable bytes. */
    private boolean imageExists(Uri uri) {
        try {
            android.os.ParcelFileDescriptor pfd =
                    getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) {
                return false;
            }
            pfd.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void pickImages() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "选择截图"), 100);
        } catch (Exception e) {
            toast("没有可用的相册应用");
        }
    }

    private void addUris(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("载入图片…");
        pd.show();
        new Thread(() -> {
            List<Item> added = new ArrayList<>();
            for (Uri u : uris) {
                String name = String.valueOf(u.getLastPathSegment());
                File f = MediaStoreUtil.cacheImage(this, u, name);
                if (f == null) {
                    continue;
                }
                Item it = new Item();
                it.path = f.getAbsolutePath();
                it.label = name;
                it.bitmap = StitchEngine.decodeSampled(it.path, 900);
                if (it.bitmap == null) {
                    continue;
                }
                added.add(it);
            }
            ui.post(() -> {
                pd.dismiss();
                if (added.isEmpty()) {
                    toast("图片载入失败");
                    return;
                }
                items.addAll(added);
                // "first full, rest linked": all newly added shots after the
                // first share one default window (lower half, where subtitles
                // usually sit). This gives the user a visible, non-edge band to
                // grab instead of a full-height image with handles at the rim.
                if (items.size() > 1 && sharedCrop == null) {
                    sharedCrop = StitchEngine.defaultLinkedCrop();
                }
                relinkCrops();
                rebuildList();
            });
        }).start();
    }

    // ---------------------------------------------------------------- list

    private void rebuildList() {
        listLayout.removeAllViews();
        if (items.isEmpty()) {
            listLayout.addView(emptyHint);
            return;
        }
        // Grid: group cells into rows of `columns()` so the user can compare
        // several screenshots at once instead of one full-width image per row.
        int cols = columns();
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it.view == null) {
                it.view = new ItemView(it);
            }
            it.view.bind(i, items.size());
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, cellHeight());
                rlp.bottomMargin = dp(6);
                listLayout.addView(row, rlp);
            }
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            clp.setMargins(dp(3), 0, dp(3), 0);
            // detach first: a view can only have one parent
            if (it.view.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) it.view.getParent()).removeView(it.view);
            }
            row.addView(it.view, clp);
        }
        // pad the last row so cells do not stretch wider than a full row
        int used = items.size() % cols;
        if (row != null && used != 0) {
            for (int k = used; k < cols; k++) {
                View spacer = new View(this);
                row.addView(spacer, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }
        }
    }

    /** 2 columns in portrait, 3-4 in landscape depending on width. */
    private int columns() {
        int w = getResources().getDisplayMetrics().widthPixels;
        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        if (!landscape) {
            return 2;
        }
        return w >= 1100 ? 4 : 3;
    }

    /** Row height for a grid cell: keeps phone screenshots readable. */
    private int cellHeight() {
        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int h = getResources().getDisplayMetrics().heightPixels;
        return (int) (h * (landscape ? 0.52f : 0.34f));
    }

    private void moveItem(int from, int to) {
        if (to < 0 || to >= items.size() || from == to) {
            return;
        }
        Item it = items.remove(from);
        items.add(to, it);
        relinkCrops();
        rebuildList();
    }

    private void removeItem(int index) {
        if (index < 0 || index >= items.size()) {
            return;
        }
        Item it = items.remove(index);
        if (it.bitmap != null) {
            it.bitmap.recycle();
        }
        relinkCrops();
        rebuildList();
    }

    /**
     * Apply a crop change made on one item.
     *
     * The first image always keeps its full frame so the series starts with the
     * original picture. Every later image shares ONE window: dragging any of
     * them updates {@link #sharedCrop} and is mirrored onto all its siblings, so
     * the same band is cut from every subsequent screenshot.
     */
    private void applyCrop(Item source) {
        if (source == null) {
            return;
        }
        int idx = items.indexOf(source);
        if (idx < 0) {
            // The zoom editor works on a scratch copy; resolve it by path.
            idx = indexOfPath(source.path);
        }
        if (idx < 0) {
            return;
        }
        Item target = items.get(idx);
        if (idx == 0) {
            // the first image is the full frame by design
            target.crop = StitchEngine.fullCrop();
            if (target.view != null) {
                target.view.cropView.invalidate();
            }
            return;
        }
        sharedCrop = source.crop.copy();
        target.crop = sharedCrop.copy();
        for (Item it : items) {
            if (it == target) {
                continue;
            }
            if (items.indexOf(it) == 0) {
                it.crop = StitchEngine.fullCrop();
            } else {
                it.crop = sharedCrop.copy();
            }
            if (it.view != null) {
                it.view.cropView.invalidate();
            }
        }
    }

    private int indexOfPath(String path) {
        if (path == null) {
            return -1;
        }
        for (int i = 0; i < items.size(); i++) {
            if (path.equals(items.get(i).path)) {
                return i;
            }
        }
        return -1;
    }

    private void openZoomEditorItem(Item item) {
        openZoomEditor(item);
    }

    /** Re-establish linked windows after the list changes (add / remove / move). */
    private void relinkCrops() {
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            it.linked = i > 0;
            if (i == 0) {
                it.crop = StitchEngine.fullCrop();
            } else {
                it.crop = sharedCrop.copy();
            }
            if (it.view != null) {
                it.view.cropView.invalidate();
            }
        }
    }

    // -------------------------------------------------------------- export

    private void export() {
        if (items.isEmpty()) {
            toast("请先添加图片");
            return;
        }
        List<String> paths = new ArrayList<>();
        List<StitchEngine.CropWindow> crops = new ArrayList<>();
        for (Item it : items) {
            paths.add(it.path);
            crops.add(it.crop.copy());
        }
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在拼接…");
        pd.show();
        new Thread(() -> {
            Bitmap out = StitchEngine.render(paths, crops, 1080, 0, Color.BLACK);
            String saved = null;
            if (out != null) {
                String name = "stitch_" + System.currentTimeMillis() + ".jpg";
                saved = MediaStoreUtil.saveBitmap(this, out, name);
            }
            final Bitmap fin = out;
            final String finSaved = saved;
            ui.post(() -> {
                pd.dismiss();
                if (fin == null) {
                    toast("拼接失败（内存不足或图片损坏）");
                    return;
                }
                showResult(fin, finSaved);
            });
        }).start();
    }

    private void showResult(Bitmap bmp, String savedUri) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        box.setBackgroundColor(Color.parseColor("#1A1F26"));

        // Show the stitched long image at its natural aspect ratio inside a
        // scroll view, starting at the TOP. FIT_CENTER on a fixed-height view
        // would shrink the whole strip into an unreadable thumbnail (and, when
        // the parent is scrollable, jump to the bottom).
        ImageView iv = new ImageView(this) {
            @Override
            protected void onMeasure(int wSpec, int hSpec) {
                int w = MeasureSpec.getSize(wSpec);
                int needed = bmp.getHeight() == 0 ? w
                        : (int) ((long) w * bmp.getHeight() / bmp.getWidth());
                setMeasuredDimension(w, Math.max(needed, 1));
            }
        };
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        iv.setAdjustViewBounds(false);
        iv.setImageBitmap(bmp);

        ScrollView sv = new ScrollView(this);
        sv.addView(iv, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView info = new TextView(this);
        info.setText(savedUri != null
                ? ("已保存到相册 · " + StitchEngine.describe(bmp))
                : ("尺寸 " + StitchEngine.describe(bmp) + "（保存失败）"));
        info.setTextColor(Color.parseColor("#8A94A6"));
        info.setTextSize(12);
        info.setPadding(0, dp(6), 0, 0);
        box.addView(info);

        Button close = small("关闭");
        close.setOnClickListener(v -> finish());
        box.addView(close);

        android.app.Dialog d = new android.app.Dialog(this,
                android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        d.setContentView(box);
        d.setOnDismissListener(x -> {
            if (bmp != null && !bmp.isRecycled()) {
                bmp.recycle();
            }
        });
        if (d.getWindow() != null) {
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.88));
        }
        d.show();
        // start at the top of the long image
        sv.post(() -> sv.scrollTo(0, 0));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /**
     * Full-screen zoomed crop editor for precise adjustment.
     *
     * The grid cells are small, so handle dragging there is fiddly. Tapping a
     * cell opens this large view where the image fills the screen and the two
     * handles are easy to hit. Edits flow through {@link #applyCrop}, so the
     * linked crops on all later images update in lockstep -- exactly like
     * dragging in the grid.
     */
    private void openZoomEditor(final Item item) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.parseColor("#0E1116"));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), dp(8), dp(8), dp(8));
        TextView t = new TextView(this);
        t.setText("精细调节裁剪区");
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button done = small("完成");
        head.addView(done);
        box.addView(head);

        final android.app.Dialog d = new android.app.Dialog(this,
                android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);

        // A dedicated crop view bound to a scratch Item whose crop we copy back.
        final Item scratch = new Item();
        scratch.bitmap = item.bitmap;
        scratch.path = item.path;
        scratch.crop = item.crop.copy();
        final CropView zoom = new CropView(scratch);
        zoom.zoomMode = true;
        zoom.cropListener = () -> {
            item.crop = scratch.crop.copy();
            applyCrop(item);
        };
        box.addView(zoom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = new TextView(this);
        hint.setText("拖动上下两个圆点调整保留区域 · 中间可整体平移");
        hint.setTextColor(Color.parseColor("#8A94A6"));
        hint.setTextSize(12);
        hint.setPadding(dp(12), dp(6), dp(12), dp(10));
        box.addView(hint);

        done.setOnClickListener(v -> {
            applyCrop(item);
            rebuildList();
            d.dismiss();
        });
        d.setContentView(box);
        d.setOnDismissListener(x -> {
            applyCrop(item);
            rebuildList();
        });
        if (d.getWindow() != null) {
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
        d.show();
    }

    // ================================================================ views

    /**
     * One cell of the grid: the image with a draggable crop window plus compact
     * per-item controls (up / down / remove).
     *
     * The list is a grid rather than one row per image so several shots can be
     * compared at once: 2 columns in portrait, 3-4 in landscape. Each cell's
     * preview keeps a tall-ish aspect so the subtitle band is still readable.
     */
    private final class ItemView extends LinearLayout {
        private final Item item;
        private final CropView cropView;
        private final TextView title;

        ItemView(Item it) {
            super(StitchActivity.this);
            this.item = it;
            setOrientation(VERTICAL);
            setBackgroundColor(Color.parseColor("#1A1F26"));
            setPadding(dp(5), dp(5), dp(5), dp(5));
            // tapping the cell body (not a handle) opens the zoomed editor
            setOnClickListener(v -> openZoomEditorItem(item));

            LinearLayout head = new LinearLayout(StitchActivity.this);
            head.setOrientation(HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            title = new TextView(StitchActivity.this);
            title.setTextColor(Color.parseColor("#C7CFDB"));
            title.setTextSize(11);
            title.setSingleLine(true);
            title.setPadding(0, 0, dp(2), 0);
            head.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button del = mini("✕");
            del.setOnClickListener(v -> removeItem(items.indexOf(item)));
            head.addView(del);
            addView(head);

            cropView = new CropView(it);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            clp.topMargin = dp(4);
            addView(cropView, clp);
        }

        void bind(int index, int total) {
            title.setText((index + 1) + " " + item.label);
            title.setTextColor(index == 0
                    ? Color.parseColor("#FFD666") : Color.parseColor("#C7CFDB"));
            boolean first = index == 0;
            // the first image keeps the full frame; hide its remove button only
            // when it would leave nothing behind, keep reorder controls minimal
            cropView.invalidate();
        }

        private Button mini(String text) {
            Button b = new Button(StitchActivity.this);
            b.setText(text);
            b.setAllCaps(false);
            b.setTextSize(10);
            b.setTextColor(Color.WHITE);
            b.setBackgroundColor(Color.parseColor("#2A3038"));
            b.setPadding(0, 0, 0, 0);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            LayoutParams lp = new LayoutParams(dp(26), dp(26));
            lp.setMargins(dp(2), 0, 0, 0);
            b.setLayoutParams(lp);
            return b;
        }
    }

    /**
     * Draws the image fitted to the view, dims the excluded bands, and provides
     * two draggable handles for the crop window.
     */
    private final class CropView extends View {
        private final Item item;
        private final Paint dim = new Paint();
        private final Paint line = new Paint();
        private final Paint handle = new Paint();
        private final RectF dst = new RectF();
        private int dragging = 0;   // 0 none, 1 top, 2 bottom, 3 move band
        private float dragStartY;
        private float dragStartTop;
        private float dragStartBottom;

        CropView(Item it) {
            super(StitchActivity.this);
            this.item = it;
            dim.setColor(0xAA000000);
            line.setColor(0xFFFFD666);
            line.setStrokeWidth(dp(2));
            line.setAntiAlias(true);
            handle.setColor(0xFFFFD666);
            handle.setAntiAlias(true);
        }

        /** Set by the zoomed editor to push changes back; null in the grid. */
        Runnable cropListener;
        /** true inside the full-screen zoom editor. */
        boolean zoomMode;

        /** Notify the owner (zoom editor) that the crop changed. */
        private void fireCropChanged() {
            if (cropListener != null) {
                cropListener.run();
            }
        }

        private boolean isZoom() {
            return zoomMode;
        }

        private void computeDst() {
            if (item.bitmap == null) {
                dst.setEmpty();
                return;
            }
            float vw = getWidth(), vh = getHeight();
            float iw = item.bitmap.getWidth(), ih = item.bitmap.getHeight();
            // A phone screenshot is very tall. FIT_CENTER inside a wide grid cell
            // wastes most of the cell as empty margin. Instead we fill the cell
            // horizontally (cover the width) and let the image extend vertically,
            // anchoring at the TOP so the handles/labels near the top stay
            // reachable; the band itself is always drawn.
            float scale = vw / iw;
            if (ih * scale < vh * 0.6f) {
                // very wide source: fall back to containment so it is readable
                scale = Math.min(vw / iw, vh / ih);
            }
            float w = iw * scale, h = ih * scale;
            float l = (vw - w) / 2f;
            float t = 0f;                       // anchor to top
            dst.set(l, t, l + w, t + h);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            computeDst();
            if (dst.isEmpty()) {
                return;
            }
            // The destination may extend below the cell when the source is very
            // tall: clip so nothing bleeds into the next grid row.
            c.save();
            c.clipRect(0, 0, getWidth(), getHeight());
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(item.bitmap, new Rect(0, 0, item.bitmap.getWidth(),
                    item.bitmap.getHeight()), dst, p);

            float y0 = dst.top + item.crop.top * dst.height();
            float y1 = dst.top + item.crop.bottom * dst.height();
            // dim above and below the kept band
            c.drawRect(dst.left, dst.top, dst.right, y0, dim);
            c.drawRect(dst.left, y1, dst.right, dst.bottom, dim);
            // band borders
            c.drawLine(dst.left, y0, dst.right, y0, line);
            c.drawLine(dst.left, y1, dst.right, y1, line);
            // grips
            float r = dp(9);
            c.drawCircle(dst.centerX(), y0, r, handle);
            c.drawCircle(dst.centerX(), y1, r, handle);
            c.restore();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (dst.isEmpty()) {
                return false;
            }
            float y0 = dst.top + item.crop.top * dst.height();
            float y1 = dst.top + item.crop.bottom * dst.height();
            // Generous grab radius: the handles are small circles, but a user
            // aiming at them should not have to be pixel-perfect.
            float touch = dp(40);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (isFirstItem() && !isZoom()) {
                        // first image always keeps the full frame; a tap on it
                        // opens the zoomed preview anyway
                        return false;
                    }
                    if (Math.abs(e.getY() - y0) <= touch) {
                        dragging = 1;
                    } else if (Math.abs(e.getY() - y1) <= touch) {
                        dragging = 2;
                    } else if (isZoom() && e.getY() > y0 && e.getY() < y1) {
                        // in the zoomed editor, grabbing the middle pans the
                        // whole window
                        dragging = 3;
                        dragStartY = e.getY();
                        dragStartTop = item.crop.top;
                        dragStartBottom = item.crop.bottom;
                    } else {
                        dragging = 4;   // potential tap -> zoom editor
                        tapY = e.getY();
                    }
                    if (dragging == 1 || dragging == 2 || dragging == 3) {
                        if (getParent() != null) {
                            getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        return true;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging == 0) {
                        return false;
                    }
                    if (dragging == 4) {
                        if (Math.abs(e.getY() - tapY) > dp(24)) {
                            dragging = 0;
                            if (getParent() != null) {
                                getParent().requestDisallowInterceptTouchEvent(false);
                            }
                            return false;
                        }
                        return true;
                    }
                    float frac = StitchEngine.clamp01((e.getY() - dst.top) / dst.height());
                    if (dragging == 1) {
                        item.crop.top = Math.min(frac, item.crop.bottom - MIN_BAND);
                    } else if (dragging == 2) {
                        item.crop.bottom = Math.max(frac, item.crop.top + MIN_BAND);
                    } else {
                        float h = dragStartBottom - dragStartTop;
                        float dy = (e.getY() - dragStartY) / dst.height();
                        float newTop = dragStartTop + dy;
                        newTop = Math.max(0f, Math.min(1f - h, newTop));
                        item.crop.top = newTop;
                        item.crop.bottom = newTop + h;
                    }
                    item.crop.top = StitchEngine.clamp01(item.crop.top);
                    item.crop.bottom = StitchEngine.clamp01(item.crop.bottom);
                    fireCropChanged();
                    if (!isZoom()) {
                        applyCrop(item);
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging == 4) {
                        dragging = 0;
                        if (getParent() != null) {
                            getParent().requestDisallowInterceptTouchEvent(false);
                        }
                        openZoomEditor(item);
                        return true;
                    }
                    dragging = 0;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    fireCropChanged();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = 0;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    return true;
            }
            return false;
        }

        private float tapY;
        private float tapStartTop;
        private float tapStartBottom;

        private boolean isFirstItem() {
            return !items.isEmpty() && items.get(0) == item;
        }
    }
}
