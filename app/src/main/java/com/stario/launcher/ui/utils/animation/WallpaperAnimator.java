/*
 * Copyright (C) 2025 Răzvan Albu
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

import android.app.Activity;
import android.os.IBinder;
import android.util.Log;
import android.view.Window;

import androidx.core.math.MathUtils;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.stario.launcher.hidden.WallpaperManagerHidden;

import dev.rikka.tools.refine.Refine;

public class WallpaperAnimator {
    private static final String TAG = "WallpaperAnimation";
    private static final float SPRING_STIFFNESS = 340f;
    private static final float SPRING_DAMPING = SpringForce.DAMPING_RATIO_NO_BOUNCY;

    private static WallpaperManagerHidden wallpaperManager;
    private static boolean hasLoggedMissingMethod = false;
    private static float lastRecordedZoomValue = 0f;
    private static SpringAnimation zoomSpring;
    private static FloatValueHolder zoomHolder;
    private static IBinder currentToken;

    public static void updateZoom(Activity activity, float zoom) {
        if (hasLoggedMissingMethod || activity == null) {
            return;
        }

        IBinder token = getWindowToken(activity);
        if (token == null || !token.isBinderAlive()) {
            return;
        }
        currentToken = token;

        getWallpaperManager(activity);
        zoom = MathUtils.clamp(zoom, 0f, 1f);

        if (zoomSpring == null) {
            zoomHolder = new FloatValueHolder(lastRecordedZoomValue);
            zoomSpring = new SpringAnimation(zoomHolder);

            SpringForce spring = new SpringForce();
            spring.setStiffness(SPRING_STIFFNESS);
            spring.setDampingRatio(SPRING_DAMPING);
            zoomSpring.setSpring(spring);
            zoomSpring.setMinimumVisibleChange(0.002f);

            zoomSpring.addUpdateListener((animation, value, velocity) -> {
                lastRecordedZoomValue = MathUtils.clamp(value, 0f, 1f);
                applyZoomInternal(currentToken, lastRecordedZoomValue);
            });
        }

        if (Math.abs(zoom - lastRecordedZoomValue) < 0.001f && !zoomSpring.isRunning()) {
            return;
        }

        zoomSpring.animateToFinalPosition(zoom);
    }

    public static void updateZoomImmediate(Activity activity, float zoom) {
        if (hasLoggedMissingMethod || activity == null) {
            return;
        }

        IBinder token = getWindowToken(activity);
        if (token == null || !token.isBinderAlive()) {
            return;
        }
        currentToken = token;

        getWallpaperManager(activity);
        float clamped = MathUtils.clamp(zoom, 0f, 1f);

        if (zoomSpring != null && zoomSpring.isRunning()) {
            zoomSpring.cancel();
        }
        if (zoomHolder != null) {
            zoomHolder.setValue(clamped);
        }
        lastRecordedZoomValue = clamped;
        applyZoomInternal(token, clamped);
    }

    private static void applyZoomInternal(IBinder token, float zoom) {
        if (token == null || !token.isBinderAlive() || wallpaperManager == null) {
            return;
        }
        try {
            wallpaperManager.setWallpaperZoomOut(token, zoom);
        } catch (NoSuchMethodError exception) {
            if (!hasLoggedMissingMethod) {
                Log.e(TAG, "WallpaperManager::setWallpaperZoomOut does not exist. This error message will not be shown again.");
                hasLoggedMissingMethod = true;
            }
            if (zoomSpring != null) {
                zoomSpring.cancel();
            }
        } catch (Throwable ignored) {
            // Window token can temporarily become invalid during transitions
        }
    }

    private static WallpaperManagerHidden getWallpaperManager(Activity activity) {
        if (wallpaperManager != null) {
            return wallpaperManager;
        }

        wallpaperManager = Refine.unsafeCast(WallpaperManagerHidden.getInstance(activity.getApplicationContext()));
        return wallpaperManager;
    }

    private static IBinder getWindowToken(Activity activity) {
        Window window = activity.getWindow();

        if (window != null) {
            return window.getDecorView().getWindowToken();
        }

        return null;
    }
}
