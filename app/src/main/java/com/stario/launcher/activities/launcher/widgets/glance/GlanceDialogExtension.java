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

package com.stario.launcher.activities.launcher.widgets.glance;

import android.app.Dialog;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.annotation.FloatRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.math.MathUtils;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.fragment.app.DialogFragment;

import com.stario.launcher.preferences.Vibrations;
import com.stario.launcher.themes.ThemedActivity;
import com.stario.launcher.ui.Measurements;
import com.stario.launcher.ui.common.glance.GlanceConstraintLayout;
import com.stario.launcher.ui.dialogs.PersistentFullscreenDialog;
import com.stario.launcher.ui.utils.HomeWatcher;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public abstract class GlanceDialogExtension extends DialogFragment
        implements GlanceExtension {
    private static final float SPRING_STIFFNESS = 380f;
    private static final float SPRING_DAMPING = SpringForce.DAMPING_RATIO_NO_BOUNCY;

    private final View.OnLayoutChangeListener layoutChangeListener;
    private final List<TransitionListener> listeners;

    protected ThemedActivity activity;

    private PersistentFullscreenDialog dialog;
    private GlanceConstraintLayout container;
    private FloatValueHolder progressHolder;
    private SpringAnimation progressSpring;
    private float collapsedScale;
    private float currentProgress;
    private float targetTranslationY;
    private HomeWatcher homeWatcher;
    private boolean isHiding;
    private Glance glance;

    protected GlanceDialogExtension() {
        super();

        this.listeners = new CopyOnWriteArrayList<>();
        this.targetTranslationY = 0;
        this.collapsedScale = 0f;
        this.currentProgress = 0f;
        this.isHiding = false;

        this.layoutChangeListener = (v, left, top, right, bottom,
                                     oldLeft, oldTop, oldRight, oldBottom) -> {
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                recalculatePosition();
            }
        };
    }

    @Nullable
    @Override
    public PersistentFullscreenDialog getDialog() {
        return dialog;
    }

    public void attach(Glance glance) {
        this.glance = glance;

        show(glance.getActivity().getSupportFragmentManager(), getTag());
        glance.attachViewExtension(getViewExtensionPreview(), v -> show());
    }

    @Override
    public void onAttach(@NonNull Context context) {
        if (!(context instanceof ThemedActivity)) {
            throw new RuntimeException("Parent activity is not of type ThemedActivity.");
        }

        activity = (ThemedActivity) context;

        homeWatcher = new HomeWatcher(activity);
        homeWatcher.setOnHomePressedListener(this::hide);
        homeWatcher.startWatch();

        super.onAttach(context);
    }

    @Override
    public void onDetach() {
        homeWatcher.stopWatch();

        super.onDetach();
    }

    @Override
    public void dismiss() {
        hide();
    }

    @Override
    public void onStop() {
        hide(false);

        super.onStop();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            dismissAllowingStateLoss();
        }

        super.onCreate(null);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        dialog = new PersistentFullscreenDialog(activity, getTheme(), true);
        dialog.setOnBackPressed(() -> {
            hide();

            return false;
        });

        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        ConstraintLayout root = new ConstraintLayout(activity);
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        this.container = inflateExpanded(inflater, root);
        this.container.addOnLayoutChangeListener(layoutChangeListener);
        root.addView(this.container);

        updateScalingInternal(0);
        root.setOnClickListener(v -> hide());

        return root;
    }

    @Override
    public void onDestroyView() {
        if (progressSpring != null) {
            progressSpring.cancel();
            progressSpring = null;
        }
        if (container != null) {
            container.removeOnLayoutChangeListener(layoutChangeListener);
        }

        super.onDestroyView();
    }

    private void recalculatePosition() {
        View anchor = glance.getRootView();

        if (anchor != null && dialog != null && dialog.isShowing()) {
            int[] location = new int[2];
            anchor.getLocationInWindow(location);
            updateLayout(location, anchor.getWidth(), anchor.getHeight());

            if (!isHiding && container.getScaleY() == 1f) {
                container.setTranslationY(targetTranslationY);
            }
        }
    }

    void updateLayout(int[] location, int width, int height) {
        ConstraintLayout.LayoutParams params =
                ((ConstraintLayout.LayoutParams) container.getLayoutParams());
        Window window = dialog.getWindow();

        if (window == null) {
            return;
        }

        View decorView = window.getDecorView();
        int screenHeight = decorView.getMeasuredHeight();
        int expandedHeight = container.getMeasuredHeight();

        int sysUIHeight = Measurements.getSysUIHeight();
        int navHeight = Measurements.getNavHeight();
        int safeAreaBottom = screenHeight - navHeight;

        container.setMaxRadius(height / 2f);
        params.width = width;
        targetTranslationY = 0f;

        int gravity = Gravity.BOTTOM;
        if (expandedHeight > (location[1] - sysUIHeight)) {
            gravity = Gravity.TOP;
        }

        if (gravity == Gravity.BOTTOM) {
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
            params.topToTop = ConstraintLayout.LayoutParams.UNSET;
            params.leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID;

            params.bottomMargin = screenHeight - location[1] - height;
            params.leftMargin = location[0];
            container.setPivotY(expandedHeight);

            int topEdge = location[1] + height - expandedHeight;
            if (topEdge < sysUIHeight) {
                targetTranslationY = sysUIHeight - topEdge;
            }
        } else {
            params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
            params.leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID;

            params.topMargin = location[1];
            params.leftMargin = location[0];
            container.setPivotY(0);

            int bottomEdge = location[1] + expandedHeight;
            if (bottomEdge > safeAreaBottom) {
                targetTranslationY = -(bottomEdge - safeAreaBottom);
            }
        }

        container.setLayoutParams(params);
    }

    private void ensureProgressSpring() {
        if (progressSpring != null) {
            return;
        }

        progressHolder = new FloatValueHolder(currentProgress);
        progressSpring = new SpringAnimation(progressHolder);

        SpringForce force = new SpringForce();
        force.setStiffness(SPRING_STIFFNESS);
        force.setDampingRatio(SPRING_DAMPING);
        progressSpring.setSpring(force);
        progressSpring.setMinimumVisibleChange(0.001f);

        progressSpring.addUpdateListener((animation, value, velocity) -> {
            if (container == null) {
                return;
            }
            float fraction = MathUtils.clamp(value, 0f, 1f);
            currentProgress = fraction;

            float scaleY = collapsedScale + (1f - collapsedScale) * fraction;
            container.setScaleY(scaleY);
            container.setTranslationY(targetTranslationY * fraction);
            updateScalingInternal(fraction);
        });

        progressSpring.addEndListener((animation, canceled, value, velocity) -> {
            if (canceled || container == null) {
                return;
            }
            if (isHiding) {
                currentProgress = 0f;
                container.setScaleY(collapsedScale);
                container.setTranslationY(0f);
                updateScalingInternal(0f);
                container.post(() -> {
                    if (dialog != null && isHiding) {
                        dialog.hide();
                    }
                });
            } else {
                currentProgress = 1f;
                container.setScaleY(1f);
                container.setTranslationY(targetTranslationY);
                updateScalingInternal(1f);
            }
        });
    }

    protected void show() {
        if (dialog != null && isEnabled() &&
                (!dialog.isShowing() && dialog.showDialog() || (dialog.isShowing() && isHiding))) {
            Vibrations.getInstance().vibrate();
            isHiding = false;

            container.post(new Runnable() {
                @Override
                public void run() {
                    if (isHiding || !isAdded()) {
                        return;
                    }

                    recalculatePosition();

                    int measuredHeight = container.getMeasuredHeight();
                    float scale = measuredHeight > 0 ? glance.getHeight() / measuredHeight : 0f;

                    if (measuredHeight > 0 && scale >= 0f && !Float.isInfinite(scale)) {
                        collapsedScale = scale;
                        ensureProgressSpring();

                        if (currentProgress <= 0f) {
                            container.setScaleY(collapsedScale);
                            container.setTranslationY(0f);
                        }

                        progressHolder.setValue(currentProgress);
                        progressSpring.setStartValue(currentProgress);
                        progressSpring.animateToFinalPosition(1f);
                    } else {
                        container.post(this);
                    }
                }
            });

            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        }
    }

    protected void hide(boolean animate) {
        if (dialog != null && dialog.isShowing()) {
            if (animate) {
                hide();
            } else {
                isHiding = true;
                if (progressSpring != null) {
                    progressSpring.cancel();
                }
                currentProgress = 0f;
                updateScalingInternal(0f);

                container.post(() -> dialog.hide());
            }

            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    protected void hide() {
        hideInternal(false);
    }

    protected void urgentHide() {
        hideInternal(true);
    }

    protected boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    private void hideInternal(boolean force) {
        if (!isShowing()) {
            return;
        }

        if (isHiding && !force) {
            return;
        }

        isHiding = true;

        int measuredHeight = container.getMeasuredHeight();
        if (measuredHeight > 0) {
            collapsedScale = glance.getHeight() / measuredHeight;
        }

        ensureProgressSpring();
        if (force) {
            progressSpring.cancel();
            currentProgress = 0f;
            container.setScaleY(collapsedScale);
            container.setTranslationY(0f);
            updateScalingInternal(0f);
            container.post(() -> {
                if (dialog != null) {
                    dialog.hide();
                }
            });
        } else {
            progressHolder.setValue(currentProgress);
            progressSpring.setStartValue(currentProgress);
            progressSpring.animateToFinalPosition(0f);
        }

        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    private void updateScalingInternal(@FloatRange(from = 0f, to = 1f) float fraction) {
        float scale = 1f / container.getScaleY();

        if (!Float.isNaN(scale) && scale != Float.POSITIVE_INFINITY) {
            updateScaling(fraction, scale);
        }

        container.setRadiusPercentage(1f - fraction);
        dialog.setDimmingFactor(fraction);

        for (TransitionListener listener : listeners) {
            listener.onProgressFraction(fraction);
        }

        if (fraction == 0) {
            container.setVisibility(View.INVISIBLE);
        } else {
            container.setVisibility(View.VISIBLE);
        }

        container.invalidate();
    }

    void updateSheetSystemUI(boolean lightMode) {
        if (dialog == null) {
            return;
        }

        Window window = dialog.getWindow();

        if (window != null) {
            View decor = window.getDecorView();

            if (lightMode) {
                decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                        & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
            } else {
                decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                        | (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
            }
        }
    }

    public void addTransitionListener(TransitionListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeTransitionListener(TransitionListener listener) {
        listeners.remove(listener);
    }

    public interface TransitionListener {
        void onProgressFraction(float fraction);
    }

    abstract public String getTAG();

    abstract protected GlanceConstraintLayout inflateExpanded(LayoutInflater inflater,
                                                              ConstraintLayout container);

    abstract protected GlanceViewExtension getViewExtensionPreview();

    abstract protected boolean isEnabled();

    abstract protected void updateScaling(@FloatRange(from = 0f, to = 1f) float fraction, float scale);
}