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

package com.stario.launcher.ui.widgets;

import android.annotation.SuppressLint;
import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.widget.GridLayout;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;

import com.stario.launcher.sheet.widgets.Widget;
import com.stario.launcher.sheet.widgets.WidgetSize;
import com.stario.launcher.ui.Measurements;

@SuppressLint("ViewConstructor")
public class WidgetContainer extends RelativeLayout implements Comparable<WidgetContainer> {
    private final AppWidgetHostView host;
    private final Widget widget;
    private WidgetMap.Cell origin; // top-left
    private int lastHostWidthDp = -1;
    private int lastHostHeightDp = -1;
    private int lastAssignedWidth = -1;
    private int lastAssignedHeight = -1;
    private int lastAssignedRow = -1;
    private int lastAssignedCol = -1;

    WidgetContainer(Context context, AppWidgetHostView host, Widget widget, WidgetMap.Cell cell) {
        super(context);

        this.origin = cell;
        this.host = host;
        this.widget = widget;

        int padding = Measurements.dpToPx(10);
        setPadding(padding, padding, padding, padding);
        setRotation(180);

        addView(host);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!(getParent() instanceof WidgetGrid)) {
            throw new RuntimeException("WidgetContainer views can only be children of WidgetGrid");
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        if (!(getParent() instanceof WidgetGrid)) {
            return;
        }

        GridLayout.LayoutParams params = (GridLayout.LayoutParams) getLayoutParams();

        int cellSize = ((WidgetGrid) getParent()).getCellSize();
        int targetWidth = cellSize * widget.size.width;
        int targetHeight = cellSize * widget.size.height;

        if (lastAssignedWidth != targetWidth || lastAssignedHeight != targetHeight
                || lastAssignedRow != origin.row || lastAssignedCol != origin.column) {
            params.rowSpec = WidgetGrid.spec(origin.row, widget.size.height);
            params.columnSpec = WidgetGrid.spec(origin.column, widget.size.width);
            params.width = targetWidth;
            params.height = targetHeight;

            lastAssignedWidth = targetWidth;
            lastAssignedHeight = targetHeight;
            lastAssignedRow = origin.row;
            lastAssignedCol = origin.column;

            setLayoutParams(params);
        }

        int hostWidth = targetWidth - getPaddingLeft() - getPaddingRight();
        int hostHeight = targetHeight - getPaddingTop() - getPaddingBottom();
        float density = Measurements.getDensity();

        if (getMeasuredWidth() > 0 && getMeasuredHeight() > 0 && density > 0f) {
            int widthDp = Math.max(0, (int) (hostWidth / density));
            int heightDp = Math.max(0, (int) (hostHeight / density));

            if (widthDp != lastHostWidthDp || heightDp != lastHostHeightDp) {
                lastHostWidthDp = widthDp;
                lastHostHeightDp = heightDp;
                host.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp);
            }
        }
    }

    public WidgetSize getSize() {
        return widget.size;
    }

    public int getPosition() {
        return widget.position;
    }

    public Widget getWidget() {
        return widget;
    }

    public int getOriginRow() {
        return origin.row;
    }

    public int getOriginColumn() {
        return origin.column;
    }

    void updateOrigin(WidgetMap.Cell origin) {
        if (origin != null && !origin.equals(this.origin)) {
            this.origin = origin;

            requestLayout();
        }
    }

    @Override
    public int compareTo(@NonNull WidgetContainer container) {
        return widget.compareTo(container.widget);
    }
}
