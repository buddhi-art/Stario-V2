/*
 * Copyright (C) 2026 Răzvan Albu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>
 */

package com.stario.launcher.ui.utils.animation;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.window.SplashScreen;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.stario.launcher.activities.launcher.Launcher;
import com.stario.launcher.themes.ThemedActivity;
import com.stario.launcher.utils.Utils;

import java.lang.ref.WeakReference;

public class AppTransitionManager {
    private static final String TAG = "AppTransitionManager";
    private static final long RETURN_TIMEOUT_MS = 60_000L; // 60 seconds

    private static final AppTransitionManager INSTANCE = new AppTransitionManager();

    private ComponentName lastLaunchedComponent;
    private Rect lastLaunchedBounds;
    private WeakReference<View> lastLaunchedViewRef;
    private long lastLaunchTimeMs;

    public static AppTransitionManager getInstance() {
        return INSTANCE;
    }

    private AppTransitionManager() {
    }

    /**
     * Prepares launch options (scale up animation, source bounds, splash screen style)
     * and records the launched app metadata for return transitions.
     */
    public Bundle prepareAppLaunch(@NonNull ThemedActivity activity,
                                   @NonNull ComponentName component,
                                   @Nullable View sourceView,
                                   @Nullable Rect[] outSourceBounds) {
        Rect bounds = null;
        ActivityOptions options = null;

        if (sourceView != null && sourceView.isAttachedToWindow()
                && sourceView.getWidth() > 0 && sourceView.getHeight() > 0) {
            int[] loc = new int[2];
            sourceView.getLocationOnScreen(loc);
            bounds = new Rect(loc[0], loc[1], loc[0] + sourceView.getWidth(), loc[1] + sourceView.getHeight());

            options = ActivityOptions.makeScaleUpAnimation(
                    sourceView,
                    0,
                    0,
                    sourceView.getWidth(),
                    sourceView.getHeight()
            );

            // Subtle tactile compression on clicked icon to give instant physical feedback
            sourceView.animate()
                    .scaleX(0.92f)
                    .scaleY(0.92f)
                    .setDuration(80)
                    .withEndAction(() -> {
                        sourceView.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .setDuration(160)
                                .start();
                    })
                    .start();
        }

        if (options != null && Utils.isMinimumSDK(Build.VERSION_CODES.TIRAMISU)) {
            options.setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_ICON);
        }

        // Cache last launched app state
        lastLaunchedComponent = component;
        lastLaunchedBounds = bounds;
        lastLaunchedViewRef = sourceView != null ? new WeakReference<>(sourceView) : null;
        lastLaunchTimeMs = SystemClock.elapsedRealtime();

        if (outSourceBounds != null && outSourceBounds.length > 0) {
            outSourceBounds[0] = bounds;
        }

        return options != null ? options.toBundle() : null;
    }

    /**
     * Called when returning to Launcher (via onNewIntent or onResume).
     * Restores wallpaper zoom and plays landmark return bounce on the icon.
     */
    public void onReturnToHome(@NonNull Launcher launcher) {
        long now = SystemClock.elapsedRealtime();
        boolean isRecentLaunch = (now - lastLaunchTimeMs) < RETURN_TIMEOUT_MS;

        // 1. Smoothly restore wallpaper zoom
        WallpaperAnimator.updateZoom(launcher, 0.0f);

        // 2. Animate returning icon if view is still accessible
        if (isRecentLaunch && lastLaunchedViewRef != null) {
            View view = lastLaunchedViewRef.get();
            if (view != null && view.isAttachedToWindow() && view.getVisibility() == View.VISIBLE) {
                playLandmarkReturnAnimation(view);
            }
        }

        // Clear cached reference to avoid holding views
        lastLaunchedViewRef = null;
        lastLaunchedComponent = null;
        lastLaunchedBounds = null;
        lastLaunchTimeMs = 0;
    }

    /**
     * Landmark Return Animation:
     * Plays a fluid spring overshoot animation (1.20x -> 1.0x) on the returning icon,
     * creating the physical sensation of the closing app window compressing into its home icon.
     */
    private void playLandmarkReturnAnimation(@NonNull View view) {
        view.setScaleX(1.20f);
        view.setScaleY(1.20f);

        SpringAnimation springX = new SpringAnimation(view, DynamicAnimation.SCALE_X, 1.0f);
        springX.getSpring().setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY);
        springX.getSpring().setStiffness(SpringForce.STIFFNESS_LOW);

        SpringAnimation springY = new SpringAnimation(view, DynamicAnimation.SCALE_Y, 1.0f);
        springY.getSpring().setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY);
        springY.getSpring().setStiffness(SpringForce.STIFFNESS_LOW);

        springX.start();
        springY.start();
    }

    @Nullable
    public ComponentName getLastLaunchedComponent() {
        return lastLaunchedComponent;
    }

    @Nullable
    public Rect getLastLaunchedBounds() {
        return lastLaunchedBounds;
    }
}
