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
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.UserHandle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.window.SplashScreen;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.stario.launcher.activities.launcher.Launcher;
import com.stario.launcher.hidden.RemoteTransitionHelper;
import com.stario.launcher.themes.ThemedActivity;
import com.stario.launcher.ui.Measurements;
import com.stario.launcher.ui.icons.AdaptiveIconView;
import com.stario.launcher.utils.RootManager;
import com.stario.launcher.utils.Utils;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

public class AppTransitionManager {
    private static final String TAG = "AppTransitionManager";
    private static final long RETURN_TIMEOUT_MS = 60_000L; // 60 seconds

    private static final AppTransitionManager INSTANCE = new AppTransitionManager();

    private final WeakHashMap<View, SpringAnimation[]> activeViewSprings = new WeakHashMap<>();

    private ComponentName lastLaunchedComponent;
    private UserHandle lastLaunchedProfile;
    private String lastLaunchedTransitionName;
    private Rect lastLaunchedBounds;
    private WeakReference<View> lastLaunchedViewRef;
    private WeakReference<ViewGroup> homeContainerRef;
    private long lastLaunchTimeMs;
    private boolean pendingReturnFromHome;
    private boolean remoteReturnRegistered;

    public static AppTransitionManager getInstance() {
        return INSTANCE;
    }

    private AppTransitionManager() {
    }

    /**
     * Prepares launch options (scale up / clip reveal / privileged remote transition,
     * source bounds, splash screen style) and records launched app metadata for return transitions.
     */
    @Nullable
    public Bundle prepareAppLaunch(@NonNull ThemedActivity activity,
                                   @NonNull ComponentName component,
                                   @Nullable View sourceView,
                                   @Nullable Rect[] outSourceBounds) {
        return prepareAppLaunch(activity, component, null, sourceView, outSourceBounds);
    }

    @Nullable
    public Bundle prepareAppLaunch(@NonNull ThemedActivity activity,
                                   @NonNull ComponentName component,
                                   @Nullable UserHandle profile,
                                   @Nullable View sourceView,
                                   @Nullable Rect[] outSourceBounds) {
        Rect bounds = extractScreenBounds(sourceView);
        ActivityOptions options = null;

        boolean usePrivilegedTransitions = RootManager.getInstance(activity).isRemoteTransitionsEnabled()
                && !activity.getPackageName().equals(component.getPackageName());

        if (sourceView != null && bounds != null) {
            playTactilePressAnimation(sourceView);

            if (usePrivilegedTransitions) {
                float cornerRadiusPx = estimateSourceCornerRadius(sourceView, bounds);
                options = RemoteTransitionHelper.createLaunchOptions(
                        activity,
                        bounds,
                        cornerRadiusPx,
                        createWallpaperSyncListener(activity)
                );
            }

            if (options == null) {
                try {
                    options = ActivityOptions.makeClipRevealAnimation(
                            sourceView,
                            0,
                            0,
                            sourceView.getWidth(),
                            sourceView.getHeight()
                    );
                } catch (Throwable ignored) {
                    options = ActivityOptions.makeScaleUpAnimation(
                            sourceView,
                            0,
                            0,
                            sourceView.getWidth(),
                            sourceView.getHeight()
                    );
                }
            }
        } else {
            options = ActivityOptions.makeBasic();
        }

        if (options != null && Utils.isMinimumSDK(Build.VERSION_CODES.TIRAMISU)) {
            options.setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_ICON);
        }

        // Synchronize wallpaper zoom out immediately on launch
        if (!usePrivilegedTransitions || options == null) {
            WallpaperAnimator.updateZoom(activity, 1.0f);
        }

        // Cache last launched app state
        lastLaunchedComponent = component;
        lastLaunchedProfile = profile;
        lastLaunchedTransitionName = (profile != null)
                ? (component.getPackageName() + profile)
                : null;
        lastLaunchedBounds = bounds;
        lastLaunchedViewRef = sourceView != null ? new WeakReference<>(sourceView) : null;
        lastLaunchTimeMs = SystemClock.elapsedRealtime();
        pendingReturnFromHome = false;

        if (outSourceBounds != null && outSourceBounds.length > 0) {
            outSourceBounds[0] = bounds;
        }

        return options != null ? options.toBundle() : null;
    }

    @Nullable
    public Bundle prepareIntentLaunch(@NonNull ThemedActivity activity,
                                      @NonNull Intent intent,
                                      @Nullable View sourceView) {
        ComponentName component = intent.getComponent();
        if (component == null) {
            component = intent.resolveActivity(activity.getPackageManager());
        }
        if (component == null) {
            component = new ComponentName(activity.getPackageName(), "external");
        }
        return prepareAppLaunch(activity, component, null, sourceView, null);
    }

    /**
     * Marks that Launcher received a Home intent (onNewIntent) so onResume executes
     * the return morph once the Launcher surface & controller are visible.
     */
    public void markPendingReturnFromHome() {
        pendingReturnFromHome = true;
    }

    public boolean isPendingReturnFromHome() {
        return pendingReturnFromHome;
    }

    public boolean isRemoteReturnAnimating() {
        return RemoteTransitionHelper.isRemoteReturnAnimating();
    }

    public void syncRemoteReturnRegistration(@NonNull Launcher launcher, @Nullable ViewGroup homeContainer) {
        this.homeContainerRef = homeContainer != null ? new WeakReference<>(homeContainer) : null;
        boolean shouldEnable = RootManager.getInstance(launcher).isRemoteTransitionsEnabled();

        if (shouldEnable && !remoteReturnRegistered) {
            remoteReturnRegistered = RemoteTransitionHelper.registerLauncherReturnAnimations(
                    launcher,
                    new RemoteTransitionHelper.ReturnTargetResolver() {
                        @Override
                        public Rect resolveReturnBounds(ComponentName closingComponent, Rect screenFallback) {
                            ViewGroup container = homeContainerRef != null ? homeContainerRef.get() : null;
                            View target = resolveReturnTargetView(launcher, container, closingComponent);
                            if (target != null && target != container) {
                                Rect r = extractScreenBounds(target);
                                if (r != null) {
                                    return r;
                                }
                            }
                            if (lastLaunchedBounds != null && lastLaunchedBounds.width() > 0) {
                                return new Rect(lastLaunchedBounds);
                            }
                            if (screenFallback != null) {
                                int iconSize = AdaptiveIconView.getMaxIconSize();
                                int cx = screenFallback.centerX();
                                int cy = (int) (screenFallback.height() * 0.78f);
                                return new Rect(cx - iconSize / 2, cy - iconSize / 2,
                                        cx + iconSize / 2, cy + iconSize / 2);
                            }
                            return null;
                        }

                        @Override
                        public ComponentName getLastLaunchedComponent() {
                            return lastLaunchedComponent;
                        }
                    },
                    createWallpaperSyncListener(launcher)
            );
        } else if (!shouldEnable && remoteReturnRegistered) {
            unregisterRemoteReturnTransitions(launcher);
        }
    }

    public void unregisterRemoteReturnTransitions(@NonNull Launcher launcher) {
        if (remoteReturnRegistered) {
            RemoteTransitionHelper.unregisterLauncherReturnAnimations(launcher);
            remoteReturnRegistered = false;
        }
    }

    /**
     * Legacy signature preserved; delegates to coordinated return handler.
     */
    public void onReturnToHome(@NonNull Launcher launcher) {
        onReturnToHome(launcher, null, homeContainerRef != null ? homeContainerRef.get() : null);
    }

    /**
     * Called when Launcher becomes visible and resumed so the returning morph
     * and wallpaper zoom restoration play smoothly while controller is visible.
     */
    public void onReturnToHome(@NonNull Launcher launcher,
                               @Nullable View controller,
                               @Nullable ViewGroup homeContainer) {
        if (homeContainer != null) {
            this.homeContainerRef = new WeakReference<>(homeContainer);
        }

        long now = SystemClock.elapsedRealtime();
        boolean isRecentLaunch = lastLaunchTimeMs > 0 && (now - lastLaunchTimeMs) < RETURN_TIMEOUT_MS;

        // Smoothly restore wallpaper zoom
        WallpaperAnimator.updateZoom(launcher, 0.0f);

        if (isRecentLaunch) {
            ViewGroup container = homeContainer != null
                    ? homeContainer
                    : (homeContainerRef != null ? homeContainerRef.get() : null);
            View targetView = resolveReturnTargetView(launcher, container, lastLaunchedComponent);

            if (targetView != null) {
                if (targetView == container) {
                    playHomeContainerSettle(container);
                } else {
                    playLandmarkReturnAnimation(targetView);
                }
            } else if (controller != null && controller.isAttachedToWindow()) {
                playHomeContainerSettle(controller);
            }
        }

        clearCachedLaunch();
    }

    private void clearCachedLaunch() {
        lastLaunchedViewRef = null;
        lastLaunchedComponent = null;
        lastLaunchedProfile = null;
        lastLaunchedTransitionName = null;
        lastLaunchedBounds = null;
        lastLaunchTimeMs = 0;
        pendingReturnFromHome = false;
    }

    @Nullable
    private View resolveReturnTargetView(@NonNull Launcher launcher,
                                         @Nullable ViewGroup homeContainer,
                                         @Nullable ComponentName component) {
        Window launcherWindow = launcher.getWindow();
        IBinder launcherToken = launcherWindow != null ? launcherWindow.getDecorView().getWindowToken() : null;

        // 1. Direct source view if still visible inside the Launcher window (e.g., pinned icon, Clock, Calendar)
        if (lastLaunchedViewRef != null) {
            View cached = lastLaunchedViewRef.get();
            if (cached != null && cached.isAttachedToWindow() && cached.isShown()
                    && (launcherToken == null || cached.getWindowToken() == launcherToken)) {
                return cached;
            }
        }

        // 2. If launched from Drawer / Search / Folder, try resolving a matching pinned icon on home screen
        if (homeContainer != null && homeContainer.isAttachedToWindow()) {
            String targetTransition = lastLaunchedTransitionName;
            String pkgPrefix = component != null ? component.getPackageName() : null;
            if (targetTransition != null || pkgPrefix != null) {
                View pinnedMatch = findMatchingIconInHierarchy(
                        homeContainer, launcherToken, targetTransition, pkgPrefix);
                if (pinnedMatch != null) {
                    return pinnedMatch;
                }
            }
            return homeContainer;
        }

        return null;
    }

    @Nullable
    private View findMatchingIconInHierarchy(@NonNull View root,
                                             @Nullable IBinder launcherToken,
                                             @Nullable String exactTransitionName,
                                             @Nullable String packagePrefix) {
        if (root instanceof AdaptiveIconView && root.isAttachedToWindow() && root.isShown()
                && (launcherToken == null || root.getWindowToken() == launcherToken)) {
            String name = root.getTransitionName();
            if (name != null) {
                if (exactTransitionName != null && exactTransitionName.equals(name)) {
                    return root;
                }
                if (packagePrefix != null && (name.equals(packagePrefix)
                        || name.startsWith(packagePrefix + "UserHandle{"))) {
                    return root;
                }
            }
        }

        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                View match = findMatchingIconInHierarchy(
                        group.getChildAt(i), launcherToken, exactTransitionName, packagePrefix);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private void playTactilePressAnimation(@NonNull View sourceView) {
        cancelActiveSprings(sourceView);
        sourceView.animate().cancel();

        boolean isCompact = isCompactIconView(sourceView);
        float pressedScale = isCompact ? 0.90f : 0.95f;

        sourceView.animate()
                .scaleX(pressedScale)
                .scaleY(pressedScale)
                .setDuration(55L)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .withEndAction(() -> startSpringSettle(sourceView, pressedScale, 1.0f, 500f, 0.72f))
                .start();
    }

    /**
     * Landmark Return Morph:
     * Smoothly settles a returning icon/widget back into its resting home slot with spring overshoot.
     */
    private void playLandmarkReturnAnimation(@NonNull View view) {
        cancelActiveSprings(view);
        view.animate().cancel();

        boolean isCompact = isCompactIconView(view);
        float startScale = isCompact ? 1.18f : 1.06f;
        float stiffness = isCompact ? 320f : 350f;
        float damping = isCompact ? 0.65f : 0.75f;

        view.setAlpha(1.0f);
        startSpringSettle(view, startScale, 1.0f, stiffness, damping);
    }

    private void playHomeContainerSettle(@NonNull View container) {
        cancelActiveSprings(container);
        container.animate().cancel();

        startSpringSettle(container, 0.955f, 1.0f, 340f, 0.78f);
    }

    private void startSpringSettle(@NonNull View view,
                                   float startScale,
                                   float targetScale,
                                   float stiffness,
                                   float dampingRatio) {
        cancelActiveSprings(view);
        view.setScaleX(startScale);
        view.setScaleY(startScale);

        SpringAnimation springX = new SpringAnimation(view, DynamicAnimation.SCALE_X, targetScale);
        springX.getSpring().setStiffness(stiffness);
        springX.getSpring().setDampingRatio(dampingRatio);

        SpringAnimation springY = new SpringAnimation(view, DynamicAnimation.SCALE_Y, targetScale);
        springY.getSpring().setStiffness(stiffness);
        springY.getSpring().setDampingRatio(dampingRatio);

        activeViewSprings.put(view, new SpringAnimation[]{springX, springY});
        springX.start();
        springY.start();
    }

    private void cancelActiveSprings(@NonNull View view) {
        SpringAnimation[] pair = activeViewSprings.remove(view);
        if (pair != null) {
            for (SpringAnimation anim : pair) {
                if (anim != null && anim.isRunning()) {
                    anim.cancel();
                }
            }
        }
    }

    private boolean isCompactIconView(@NonNull View view) {
        if (view instanceof AdaptiveIconView) {
            return true;
        }
        int threshold = Math.max(1, Measurements.dpToPx(96));
        return view.getWidth() <= threshold && view.getHeight() <= threshold;
    }

    private float estimateSourceCornerRadius(@NonNull View sourceView, @NonNull Rect bounds) {
        if (sourceView instanceof AdaptiveIconView) {
            return Math.min(bounds.width(), bounds.height()) * 0.28f;
        }
        return Measurements.dpToPx(20);
    }

    @Nullable
    private Rect extractScreenBounds(@Nullable View sourceView) {
        if (sourceView == null || !sourceView.isAttachedToWindow()
                || sourceView.getWidth() <= 0 || sourceView.getHeight() <= 0) {
            return null;
        }
        int[] loc = new int[2];
        sourceView.getLocationOnScreen(loc);
        return new Rect(loc[0], loc[1], loc[0] + sourceView.getWidth(), loc[1] + sourceView.getHeight());
    }

    private RemoteTransitionHelper.TransitionProgressListener createWallpaperSyncListener(@NonNull ThemedActivity activity) {
        return new RemoteTransitionHelper.TransitionProgressListener() {
            @Override
            public void onTransitionStart(boolean isOpening) {
                WallpaperAnimator.updateZoomImmediate(activity, isOpening ? 0.0f : 1.0f);
            }

            @Override
            public void onTransitionProgress(float progress, boolean isOpening) {
                WallpaperAnimator.updateZoomImmediate(activity, progress);
            }

            @Override
            public void onTransitionEnd(boolean isOpening) {
                WallpaperAnimator.updateZoom(activity, isOpening ? 1.0f : 0.0f);
            }
        };
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
