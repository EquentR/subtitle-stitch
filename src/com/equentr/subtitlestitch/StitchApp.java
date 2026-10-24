package com.equentr.subtitlestitch;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/**
 * Application entry point.
 *
 * Its only job is to track whether one of our activities is visible. The floating
 * bubble is shown ONLY outside the app, so it never covers our own screens, and
 * it becomes visible again as soon as the user leaves.
 */
public class StitchApp extends Application {

    private int started = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity a, Bundle b) { }

            @Override
            public void onActivityStarted(Activity a) {
                started++;
                if (started == 1) {
                    BubbleService.setAppForeground(true);
                    // hide the bubble while our UI is on screen
                    BubbleService.hideBadge(StitchApp.this);
                }
            }

            @Override
            public void onActivityResumed(Activity a) { }

            @Override
            public void onActivityPaused(Activity a) { }

            @Override
            public void onActivityStopped(Activity a) {
                started = Math.max(0, started - 1);
                if (started == 0) {
                    BubbleService.setAppForeground(false);
                    // back on the launcher / another app: bring the bubble back
                    if (ShotStore.isBubbleEnabled(StitchApp.this)
                            && ShotWatcherService.isRunning) {
                        BubbleService.showBadge(StitchApp.this,
                                ShotWatcherService.capturedCount());
                    }
                }
            }

            @Override
            public void onActivitySaveInstanceState(Activity a, Bundle b) { }

            @Override
            public void onActivityDestroyed(Activity a) { }
        });
    }
}
