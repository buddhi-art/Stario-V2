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

package com.stario.launcher.ui.utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import com.stario.launcher.utils.Utils;

/**
 * Legacy broadcast receiver watcher for Intent.ACTION_CLOSE_SYSTEM_DIALOGS.
 * @deprecated Deprecated on Android 12+ (API 31+) as the system dialogs broadcast is blocked for
 * 3rd-party launchers. Modern Home and gesture detection is handled via Activity.onNewIntent().
 */
@Deprecated
public class HomeWatcher {
    private final IntentFilter filter;
    private final Context context;

    private InnerReceiver receiver;
    private OnHomePressedListener listener;

    public HomeWatcher(Context context) {
        this.context = context;
        filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
    }

    public void setOnHomePressedListener(OnHomePressedListener listener) {
        this.listener = listener;
        receiver = new InnerReceiver();
    }

    public void startWatch() {
        if (receiver != null) {
            // Android 12+ restricts ACTION_CLOSE_SYSTEM_DIALOGS; onNewIntent in Launcher handles home detection.
            if (Utils.isMinimumSDK(Build.VERSION_CODES.S)) {
                return;
            }
            try {
                if (Utils.isMinimumSDK(Build.VERSION_CODES.TIRAMISU)) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    // noinspection UnspecifiedRegisterReceiverFlag
                    context.registerReceiver(receiver, filter);
                }
            } catch (Exception ignored) {
            }
        }
    }

    public void stopWatch() {
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
        }
    }

    class InnerReceiver extends BroadcastReceiver {
        final String SYSTEM_DIALOG_REASON_RECENT_APPS = "recentapps";
        final String SYSTEM_DIALOG_REASON_HOME_KEY = "homekey";

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action != null &&
                    action.equals(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) {
                String reason = intent.getStringExtra("reason");

                if (reason != null) {
                    if (listener != null) {
                        if (reason.equals(SYSTEM_DIALOG_REASON_HOME_KEY) ||
                                reason.equals(SYSTEM_DIALOG_REASON_RECENT_APPS)) {
                            listener.onHomePressed();
                        }
                    }
                }
            }
        }
    }

    public interface OnHomePressedListener {
        void onHomePressed();
    }
}