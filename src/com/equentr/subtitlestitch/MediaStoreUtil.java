package com.equentr.subtitlestitch;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * MediaStore helpers: list screenshots, copy a content:// image into our private
 * cache so the stitcher can decode straight from a File.
 */
public final class MediaStoreUtil {

    private MediaStoreUtil() { }

    /** Column name for a "date added" value that works across API levels. */
    private static String dateColumn() {
        return Build.VERSION.SDK_INT >= 29
                ? MediaStore.Images.Media.DATE_ADDED
                : MediaStore.Images.Media.DATE_MODIFIED;
    }

    /**
     * Query the most recent images that look like screenshots.
     *
     * We do NOT hard-filter on RELATIVE_PATH == "Screenshots", because some ROMs
     * (including ColorOS/OnePlus) write screenshots to other buckets. Instead we
     * match on the conventional Screenshots directory when available and fall
     * back to "recent images" so the picker is never empty.
     */
    public static List<Shot> recentImages(Context ctx, int limit) {
        List<Shot> out = new ArrayList<>();
        String[] proj = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
        };
        if (Build.VERSION.SDK_INT >= 29) {
            proj = new String[]{
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT,
                    MediaStore.Images.Media.RELATIVE_PATH,
            };
        }
        Uri base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    base, proj, null, null,
                    dateColumn() + " DESC");
            if (c == null) {
                return out;
            }
            int idxId = c.getColumnIndex(MediaStore.Images.Media._ID);
            int idxName = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
            int idxDate = c.getColumnIndex(dateColumn());
            int idxW = c.getColumnIndex(MediaStore.Images.Media.WIDTH);
            int idxH = c.getColumnIndex(MediaStore.Images.Media.HEIGHT);
            int idxPath = Build.VERSION.SDK_INT >= 29
                    ? c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH) : -1;

            while (c.moveToNext() && out.size() < limit) {
                long id = c.getLong(idxId);
                String name = idxName >= 0 ? c.getString(idxName) : ("image_" + id);
                long date = idxDate >= 0 ? c.getLong(idxDate) : 0L;
                int w = idxW >= 0 ? c.getInt(idxW) : 0;
                int h = idxH >= 0 ? c.getInt(idxH) : 0;
                String rel = idxPath >= 0 ? c.getString(idxPath) : null;
                Uri uri = ContentUris.withAppendedId(base, id);
                boolean isScreenshot = looksLikeScreenshot(name, rel);
                out.add(new Shot(id, uri, name, date, w, h, isScreenshot));
            }
        } catch (SecurityException e) {
            // permission not granted yet
            return out;
        } catch (Exception e) {
            return out;
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return out;
    }

    /** Heuristic: name or folder indicates a screenshot. */
    public static boolean looksLikeScreenshot(String name, String relativePath) {
        String lowerName = name == null ? "" : name.toLowerCase();
        String lowerPath = relativePath == null ? "" : relativePath.toLowerCase();
        return lowerPath.contains("screenshot")
                || lowerPath.contains("截屏") || lowerPath.contains("截图")
                || lowerName.startsWith("screenshot")
                || lowerName.startsWith("screencap")
                || lowerName.contains("截屏") || lowerName.contains("截图");
    }

    /** Newest matching image id, used by the watcher to spot new screenshots. */
    public static long newestImageId(Context ctx) {
        List<Shot> list = recentImages(ctx, 1);
        return list.isEmpty() ? -1L : list.get(0).id;
    }

    /** Copy the bytes behind a content Uri into a private cache file. */
    public static File cacheImage(Context ctx, Uri uri, String name) {
        // A file:// uri (our own MediaProjection capture) is already a real file.
        if (uri != null && "file".equals(uri.getScheme())) {
            String p = uri.getPath();
            if (p != null) {
                File f = new File(p);
                if (f.exists() && f.length() > 0) {
                    return f;
                }
            }
            return null;
        }
        File dir = new File(ctx.getCacheDir(), "picked");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String safe = (name == null ? "img" : name).replaceAll("[^A-Za-z0-9._-]", "_");
        File dst = new File(dir, System.currentTimeMillis() + "_" + safe);
        InputStream in = null;
        OutputStream outStream = null;
        try {
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            outStream = new FileOutputStream(dst);
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) {
                outStream.write(buf, 0, r);
            }
            outStream.flush();
            return dst;
        } catch (Exception e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            try { if (outStream != null) outStream.close(); } catch (Exception ignored) { }
        }
    }

    /** Delete stale files from the picker cache, keeping the latest keepN. */
    public static void trimCache(Context ctx, int keepN) {
        File dir = new File(ctx.getCacheDir(), "picked");
        File[] files = dir.listFiles();
        if (files == null || files.length <= keepN) {
            return;
        }
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = keepN; i < files.length; i++) {
            files[i].delete();
        }
    }

    /** True if the app can read shared images. */
    public static boolean canReadImages(Context ctx) {
        String perm = Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_IMAGES"
                : "android.permission.READ_EXTERNAL_STORAGE";
        return ctx.checkSelfPermission(perm)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Path where finished images are written, using MediaStore on API 29+. */
    public static String saveBitmap(Context ctx, android.graphics.Bitmap bmp, String displayName) {
        String rel = Environment.DIRECTORY_PICTURES + "/SubtitleStitch";
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, rel);
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = ctx.getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) {
                    return null;
                }
                OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                if (os == null) {
                    ctx.getContentResolver().delete(uri, null, null);
                    return null;
                }
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, os);
                os.close();
                cv.clear();
                cv.put(MediaStore.Images.Media.IS_PENDING, 0);
                ctx.getContentResolver().update(uri, cv, null, null);
                return uri.toString();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** One MediaStore row. */
    public static final class Shot {
        public final long id;
        public final Uri uri;
        public final String name;
        public final long dateAdded;
        public final int width;
        public final int height;
        public final boolean screenshot;
        /**
         * Non-null when the image came from our own capture (MediaProjection):
         * it lives in app cache and is addressed by path, not by MediaStore id.
         */
        public final String localPath;

        Shot(long id, Uri uri, String name, long dateAdded, int width, int height, boolean screenshot) {
            this(id, uri, name, dateAdded, width, height, screenshot, null);
        }

        Shot(long id, Uri uri, String name, long dateAdded, int width, int height,
             boolean screenshot, String localPath) {
            this.id = id;
            this.uri = uri;
            this.name = name;
            this.dateAdded = dateAdded;
            this.width = width;
            this.height = height;
            this.screenshot = screenshot;
            this.localPath = localPath;
        }

        /** Build a Shot backed by a local file from our own capture. */
        public static Shot ofFile(java.io.File f) {
            return new Shot(-1L, Uri.fromFile(f), f.getName(),
                    f.lastModified() / 1000L, 0, 0, true, f.getAbsolutePath());
        }

        /** uri usable for display / copying: file path wins when present. */
        public Uri displayUri() {
            return localPath != null ? Uri.fromFile(new java.io.File(localPath)) : uri;
        }
    }
}
