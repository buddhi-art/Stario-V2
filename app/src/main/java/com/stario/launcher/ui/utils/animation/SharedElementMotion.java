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

import android.graphics.Path;

import androidx.annotation.NonNull;
import androidx.transition.ArcMotion;
import androidx.transition.PathMotion;

public final class SharedElementMotion extends PathMotion {
    private final ArcMotion arcMotion;

    public SharedElementMotion() {
        this.arcMotion = new ArcMotion();
        this.arcMotion.setMinimumHorizontalAngle(15f);
        this.arcMotion.setMinimumVerticalAngle(0f);
        this.arcMotion.setMaximumAngle(70f);
    }

    @NonNull
    @Override
    public Path getPath(float startX, float startY, float endX, float endY) {
        return arcMotion.getPath(startX, startY, endX, endY);
    }
}