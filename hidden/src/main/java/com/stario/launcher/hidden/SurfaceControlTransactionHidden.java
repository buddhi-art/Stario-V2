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

package com.stario.launcher.hidden;

import android.graphics.Rect;
import android.view.SurfaceControl;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(SurfaceControl.Transaction.class)
public class SurfaceControlTransactionHidden {
    public SurfaceControlTransactionHidden setPosition(SurfaceControl sc, float x, float y) {
        throw new RuntimeException("Stub!");
    }

    public SurfaceControlTransactionHidden setCornerRadius(SurfaceControl sc, float cornerRadius) {
        throw new RuntimeException("Stub!");
    }

    public SurfaceControlTransactionHidden setWindowCrop(SurfaceControl sc, Rect crop) {
        throw new RuntimeException("Stub!");
    }

    public SurfaceControlTransactionHidden setWindowCrop(SurfaceControl sc, int width, int height) {
        throw new RuntimeException("Stub!");
    }

    public SurfaceControlTransactionHidden setMatrix(SurfaceControl sc,
                                                     float dsdx, float dtdx,
                                                     float dtdy, float dsdy) {
        throw new RuntimeException("Stub!");
    }
}
