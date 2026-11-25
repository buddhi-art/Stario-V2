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

package com.stario.launcher.ui.common.text;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import com.stario.launcher.ui.Measurements;
import com.stario.launcher.ui.utils.UiUtils;
import com.stario.launcher.ui.utils.animation.Animation;

public class PulsingTextView extends AppCompatTextView {
    private static final int FADE_LENGTH = 50;
    private static final int[] GRADIENT_COLORS = {
            Color.BLACK,
            Color.argb(128, 0, 0, 0),
            Color.BLACK
    };
    private static final float[] GRADIENT_POSITIONS = {0f, 0.5f, 1f};

    private ValueAnimator animator;
    private Paint gradientPaint;
    private Matrix gradientMatrix;
    private LinearGradient gradient;
    private boolean pulsating;
    private float cycleDx;
    private float cycleDy;
    private float offset;

    public PulsingTextView(Context context) {
        super(context);

        init();
    }

    public PulsingTextView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        init();
    }

    public PulsingTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        init();
    }

    private void init() {
        offset = 0f;
        pulsating = true;

        gradientMatrix = new Matrix();
        gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gradientPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(Animation.SUSTAINED.getDuration());
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(animation -> {
            if (pulsating && getVisibility() == VISIBLE && getWindowVisibility() == VISIBLE) {
                offset = (float) animation.getAnimatedValue();
                updateGradientMatrix();
                invalidate();
            }
        });
    }

    public void setPulsating(boolean pulsating) {
        this.pulsating = pulsating;

        if (pulsating) {
            updateAnimationState();
        } else {
            if (animator != null && animator.isRunning()) {
                animator.cancel();
            }
        }

        invalidate();
    }

    private void updateAnimationState() {
        if (animator == null) {
            return;
        }

        if (pulsating && UiUtils.areAnimationsOn()
                && isAttachedToWindow()
                && getVisibility() == VISIBLE
                && getWindowVisibility() == VISIBLE) {
            if (!animator.isRunning()) {
                animator.start();
            }
        } else {
            if (animator.isRunning()) {
                animator.cancel();
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        updateAnimationState();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
        }

        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);

        updateAnimationState();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);

        updateAnimationState();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);

        buildGradient(width, height);
    }

    private void buildGradient(int width, int height) {
        if (width <= 0 || height <= 0) {
            gradient = null;
            gradientPaint.setShader(null);
            return;
        }

        int fadeLength = Math.max(1, Measurements.dpToPx(FADE_LENGTH));
        int remaining = width;
        int stopCount = 0;

        while (remaining > -fadeLength) {
            stopCount += 2;
            remaining -= fadeLength;
        }

        float cycleFraction = 2f * (1f + (float) fadeLength / width) / stopCount;
        cycleDx = width * cycleFraction;
        cycleDy = height * 0.3f * cycleFraction;

        gradient = new LinearGradient(
                0f, 0f,
                cycleDx, cycleDy,
                GRADIENT_COLORS,
                GRADIENT_POSITIONS,
                Shader.TileMode.REPEAT
        );
        updateGradientMatrix();
        gradientPaint.setShader(gradient);
    }

    private void updateGradientMatrix() {
        if (gradient != null) {
            gradientMatrix.setTranslate(cycleDx * offset, cycleDy * offset);
            gradient.setLocalMatrix(gradientMatrix);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (pulsating && UiUtils.areAnimationsOn() && getVisibility() == VISIBLE) {
            int width = getWidth();
            int height = getHeight();

            if (width > 0 && height > 0) {
                if (gradient == null) {
                    buildGradient(width, height);
                }

                if (gradient != null) {
                    int count = canvas.saveLayer(0.0f, 0.0f, (float) width, (float) height, null);
                    super.onDraw(canvas);

                    canvas.drawRect(0, 0, width, height, gradientPaint);

                    canvas.restoreToCount(count);
                    return;
                }
            }
        }

        super.onDraw(canvas);
    }
}
