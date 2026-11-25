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

package com.stario.launcher.ui.common.lock;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.stario.launcher.ui.utils.animation.Animation;

import carbon.widget.ConstraintLayout;

public class ClosingAnimationView extends ConstraintLayout {
    private Paint paint;
    private Paint baseDimPaint;
    private Matrix gradientMatrix;
    private RadialGradient gradient;
    private float maxRadius;
    private float animatedValue;
    private boolean closed;
    private ValueAnimator animator;
    private float x = 0, y = 0;

    public ClosingAnimationView(Context context) {
        super(context);

        init();
    }

    public ClosingAnimationView(Context context, AttributeSet attrs) {
        super(context, attrs);

        init();
    }

    public ClosingAnimationView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        init();
    }

    public void init() {
        this.closed = false;
        this.animatedValue = 1f;
        this.paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.paint.setDither(true);
        this.baseDimPaint = new Paint();
        this.baseDimPaint.setColor(Color.BLACK);
        this.gradientMatrix = new Matrix();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }

        super.onDetachedFromWindow();
    }

    @Override
    public void dispatchDraw(@NonNull Canvas canvas) {
        super.dispatchDraw(canvas);

        if (closed) {
            if (animatedValue <= 0f) {
                canvas.drawColor(Color.BLACK);
            } else if (gradient != null) {
                int paintAlpha = Math.max(0, Math.min(255, (int) (500f * (1f - animatedValue))));
                int centerAlpha = Math.max(0, Math.min(255, (int) (paintAlpha * (1f - animatedValue))));

                if (centerAlpha > 0) {
                    baseDimPaint.setAlpha(centerAlpha);
                    canvas.drawRect(0f, 0f, getWidth(), getHeight(), baseDimPaint);
                }

                gradientMatrix.setScale(animatedValue, animatedValue, x, y);
                gradient.setLocalMatrix(gradientMatrix);

                paint.setAlpha(paintAlpha);
                canvas.drawCircle(x, y, maxRadius, paint);
            }
        }
    }

    public boolean reset() {
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }

        if (!closed) {
            return false;
        }

        closed = false;
        invalidate();

        return true;
    }

    public interface OnAnimationEnd {
        void animationEnd();
    }

    public void closeTo(float x, float y, OnAnimationEnd listener) {
        this.x = x;
        this.y = y;

        float abs = Math.max(getMeasuredWidth() - x, x);
        float ord = Math.max(getMeasuredHeight() - y, y);
        this.maxRadius = Math.max(1f, (float) Math.hypot(abs, ord));
        this.gradient = new RadialGradient(x, y, maxRadius,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP);
        this.paint.setShader(gradient);
        this.animatedValue = 1f;

        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }

        animator = ValueAnimator.ofFloat(1f, 0f);
        animator.setDuration(Animation.SHORT.getDuration());
        animator.setInterpolator(new FastOutSlowInInterpolator());

        animator.addUpdateListener(animation -> {
            animatedValue = (float) animation.getAnimatedValue();
            invalidate();
        });

        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);

                if (listener != null) {
                    listener.animationEnd();
                }
            }

            @Override
            public void onAnimationStart(Animator animation) {
                super.onAnimationStart(animation);

                closed = true;
            }
        });

        animator.start();
    }
}