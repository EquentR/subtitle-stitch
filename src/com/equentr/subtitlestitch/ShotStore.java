package com.equentr.subtitlestitch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistence for the captured-screenshot list.
 *
 * The watcher's list used to live only in a static field, so it vanished
 * whenever the process was killed (which happens constantly on Android). That
 * made "拼接已捕获的截图" open an empty editor after any restart. We now mirror
 * the list into SharedPreferences as JSON and reload it on startup.
 */
public final class ShotStore {

    private static final String PREFS = "shot_store";
    private static final String KEY_SHOTS = "shots";
    private static final String KEY_RUNNING = "watcher_running";
    private static final String KEY_BUBBLE_HIDDEN = "bubble_hidden";
    /** Master switch for the floating bubble (user-controlled in MainActivity). */
    private static final String KEY_BUBBLE_ENABLED = "bubble_enabled";
    private static final int MAX = 30;

    private ShotStore() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void save(Context c, List<MediaStoreUtil.Shot> shots) {
        JSONArray arr = new JSONArray();
        try {
            List<MediaStoreUtil.Shot> keep = shots;
            if (keep.size() > MAX) {
                keep = keep.subList(keep.size() - MAX, keep.size());
            }
            for (MediaStoreUtil.Shot s : keep) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("uri", s.uri.toString());
                o.put("name", s.name);
                o.put("date", s.dateAdded);
                o.put("w", s.width);
                o.put("h", s.height);
                if (s.localPath != null) {
                    o.put("path", s.localPath);
                }
                arr.put(o);
            }
        } catch (Exception ignored) { }
        prefs(c).edit().putString(KEY_SHOTS, arr.toString()).apply();
    }

    public static List<MediaStoreUtil.Shot> load(Context c) {
        List<MediaStoreUtil.Shot> out = new ArrayList<>();
        String raw = prefs(c).getString(KEY_SHOTS, null);
        if (raw == null) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String local = o.has("path") ? o.getString("path") : null;
                out.add(new MediaStoreUtil.Shot(
                        o.getLong("id"),
                        android.net.Uri.parse(o.getString("uri")),
                        o.optString("name", ""),
                        o.optLong("date", 0L),
                        o.optInt("w", 0),
                        o.optInt("h", 0),
                        true,
                        local));
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static void clear(Context c) {
        prefs(c).edit().remove(KEY_SHOTS).apply();
    }

    public static void setWatcherRunning(Context c, boolean running) {
        prefs(c).edit().putBoolean(KEY_RUNNING, running).apply();
    }

    public static boolean isWatcherRunning(Context c) {
        return prefs(c).getBoolean(KEY_RUNNING, false);
    }

    /**
     * Remember whether the floating bubble was hidden by a long-press, so the
     * user's choice survives a process restart / reboot.
     */
    public static void setBubbleHidden(Context c, boolean hidden) {
        prefs(c).edit().putBoolean(KEY_BUBBLE_HIDDEN, hidden).apply();
    }

    public static boolean isBubbleHidden(Context c) {
        return prefs(c).getBoolean(KEY_BUBBLE_HIDDEN, false);
    }

    public static void setBubbleEnabled(Context c, boolean enabled) {
        prefs(c).edit().putBoolean(KEY_BUBBLE_ENABLED, enabled)
                .putBoolean(KEY_BUBBLE_HIDDEN, !enabled).apply();
    }

    /** Default ON: the bubble is the primary capture entry point. */
    public static boolean isBubbleEnabled(Context c) {
        return prefs(c).getBoolean(KEY_BUBBLE_ENABLED, true);
    }
}
