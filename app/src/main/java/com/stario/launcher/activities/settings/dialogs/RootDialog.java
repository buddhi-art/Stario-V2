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

package com.stario.launcher.activities.settings.dialogs;

import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.stario.launcher.R;
import com.stario.launcher.themes.ThemedActivity;
import com.stario.launcher.ui.dialogs.ActionDialog;
import com.stario.launcher.utils.RootManager;

public class RootDialog extends ActionDialog {
    private NestedScrollView scroller;
    private ViewGroup root;
    private RootManager rootManager;
    private Resources resources;

    private TextView summaryTitle;
    private TextView providerText;
    private TextView permissionText;
    private TextView feedbackText;
    private TextView moduleStatusText;
    private TextView transitionsDesc;

    private View rootModeContainer;
    private MaterialSwitch rootModeSwitch;
    private View rootLockContainer;
    private MaterialSwitch rootLockSwitch;
    private View transitionsContainer;
    private MaterialSwitch transitionsSwitch;

    private MaterialButton refreshBtn;
    private MaterialButton installBtn;
    private MaterialButton uninstallBtn;
    private MaterialButton rebootBtn;

    private boolean rebootConfirmArmed;
    private boolean updatingUi;
    private boolean busy;
    private RootManager.RootStatus lastStatus;

    public RootDialog(@NonNull ThemedActivity activity) {
        super(activity);
    }

    @NonNull
    @Override
    protected View inflateContent(LayoutInflater inflater) {
        root = (ViewGroup) inflater.inflate(R.layout.pop_up_root_enhancements, null);

        resources = activity.getResources();
        rootManager = RootManager.getInstance(activity);

        scroller = root.findViewById(R.id.scroller);
        summaryTitle = root.findViewById(R.id.root_summary_title);
        providerText = root.findViewById(R.id.root_provider_text);
        permissionText = root.findViewById(R.id.root_permission_text);
        feedbackText = root.findViewById(R.id.root_feedback_text);
        moduleStatusText = root.findViewById(R.id.root_module_status);
        transitionsDesc = root.findViewById(R.id.root_transitions_desc);

        rootModeContainer = root.findViewById(R.id.root_mode_container);
        rootModeSwitch = root.findViewById(R.id.root_mode_switch);
        rootLockContainer = root.findViewById(R.id.root_lock_container);
        rootLockSwitch = root.findViewById(R.id.root_lock_switch);
        transitionsContainer = root.findViewById(R.id.root_transitions_container);
        transitionsSwitch = root.findViewById(R.id.root_transitions_switch);

        refreshBtn = root.findViewById(R.id.root_refresh_btn);
        installBtn = root.findViewById(R.id.root_install_module_btn);
        uninstallBtn = root.findViewById(R.id.root_uninstall_module_btn);
        rebootBtn = root.findViewById(R.id.root_reboot_btn);

        initControls();
        return root;
    }

    private void initControls() {
        setupSwitch(rootModeSwitch, rootModeContainer, rootManager.isRootModeEnabled(),
                (button, checked) -> {
                    if (updatingUi) {
                        return;
                    }
                    if (checked) {
                        setBusy(true);
                        rootManager.verifyRootAccessAsync(rooted -> {
                            setBusy(false);
                            if (activity.isDestroyed() || activity.isFinishing()) {
                                return;
                            }
                            if (rooted) {
                                rootManager.setRootModeEnabled(true);
                                hideFeedback();
                            } else {
                                rootManager.setRootModeEnabled(false);
                                updatingUi = true;
                                rootModeSwitch.setChecked(false);
                                updatingUi = false;
                                showFeedback(resources.getString(R.string.root_denied_warning));
                            }
                            bindStatus(rootManager.getCachedStatus());
                        });
                    } else {
                        rootManager.setRootModeEnabled(false);
                        hideFeedback();
                        bindStatus(lastStatus);
                    }
                });

        setupSwitch(rootLockSwitch, rootLockContainer, rootManager.isRootScreenLockEnabled(),
                (button, checked) -> {
                    if (updatingUi) {
                        return;
                    }
                    if (rootManager.isRootModeEnabled()) {
                        rootManager.setRootScreenLockEnabled(checked);
                    } else {
                        updatingUi = true;
                        button.setChecked(false);
                        updatingUi = false;
                    }
                });

        setupSwitch(transitionsSwitch, transitionsContainer, rootManager.isRemoteTransitionsEnabled(),
                (button, checked) -> {
                    if (updatingUi) {
                        return;
                    }
                    if (rootManager.hasRemoteTransitionPermission()) {
                        rootManager.setRemoteTransitionsEnabled(checked);
                    } else {
                        updatingUi = true;
                        button.setChecked(false);
                        updatingUi = false;
                    }
                });

        refreshBtn.setOnClickListener(v -> refreshStatus());

        installBtn.setOnClickListener(v -> {
            if (busy) {
                return;
            }
            setBusy(true);
            hideFeedback();
            rootManager.installSystemizeModuleAsync(result -> {
                setBusy(false);
                if (activity.isDestroyed() || activity.isFinishing()) {
                    return;
                }
                showFeedback(result.message);
                bindStatus(rootManager.getCachedStatus());
            });
        });

        uninstallBtn.setOnClickListener(v -> {
            if (busy) {
                return;
            }
            setBusy(true);
            hideFeedback();
            rootManager.uninstallSystemizeModuleAsync(result -> {
                setBusy(false);
                if (activity.isDestroyed() || activity.isFinishing()) {
                    return;
                }
                showFeedback(result.message);
                bindStatus(rootManager.getCachedStatus());
            });
        });

        rebootBtn.setOnClickListener(v -> {
            if (busy) {
                return;
            }
            if (!rebootConfirmArmed) {
                rebootConfirmArmed = true;
                rebootBtn.setText(R.string.root_reboot_confirm);
                return;
            }
            setBusy(true);
            rootManager.rebootAsync(ok -> {
                setBusy(false);
                if (!ok) {
                    rebootConfirmArmed = false;
                    rebootBtn.setText(R.string.root_reboot_device);
                    showFeedback(resources.getString(R.string.root_denied_warning));
                }
            });
        });
    }

    @Override
    public void show() {
        super.show();

        rebootConfirmArmed = false;
        rebootBtn.setText(R.string.root_reboot_device);
        hideFeedback();
        scroller.scrollTo(0, 0);

        RootManager.RootStatus cached = rootManager.getCachedStatus();
        if (cached != null) {
            bindStatus(cached);
        } else {
            summaryTitle.setText(R.string.root_status_checking);
        }
        refreshStatus();
    }

    private void refreshStatus() {
        if (busy) {
            return;
        }
        setBusy(true);
        rootManager.refreshStatusAsync(status -> {
            setBusy(false);
            if (!activity.isDestroyed() && !activity.isFinishing()) {
                bindStatus(status);
            }
        });
    }

    private void bindStatus(@Nullable RootManager.RootStatus status) {
        lastStatus = status;
        boolean rooted = status != null && status.rooted;
        boolean privPerm = status != null
                ? status.privilegedPermissionGranted
                : rootManager.hasRemoteTransitionPermission();
        boolean rootModeOn = rootManager.isRootModeEnabled() && (rooted || status == null);

        if (privPerm) {
            summaryTitle.setText(R.string.root_status_privileged);
        } else if (status != null && status.moduleState == RootManager.ModuleState.PENDING_REBOOT_INSTALL) {
            summaryTitle.setText(R.string.root_status_pending_reboot);
        } else if (status != null && status.moduleState == RootManager.ModuleState.PENDING_REBOOT_REMOVE) {
            summaryTitle.setText(R.string.root_status_pending_remove);
        } else if (rooted) {
            summaryTitle.setText(R.string.root_status_available);
        } else if (status != null) {
            summaryTitle.setText(R.string.root_status_not_rooted);
        }

        String providerName = status != null ? formatProvider(status.provider) : "—";
        providerText.setText(resources.getString(R.string.root_provider_format, providerName));
        permissionText.setText(resources.getString(R.string.root_permission_format,
                resources.getString(privPerm ? R.string.root_granted : R.string.root_not_granted)));

        // Sync switches quietly
        updatingUi = true;
        try {
            rootModeSwitch.setChecked(rootManager.isRootModeEnabled());
            rootModeSwitch.jumpDrawablesToCurrentState();

            updateChildControlState(rootLockContainer, rootLockSwitch,
                    rootModeOn && rooted, rootManager.isRootScreenLockEnabled());

            updateChildControlState(transitionsContainer, transitionsSwitch,
                    privPerm, rootManager.isRemoteTransitionsEnabled());
        } finally {
            updatingUi = false;
        }
        transitionsDesc.setText(privPerm
                ? R.string.root_remote_transitions_description
                : R.string.root_remote_transitions_locked);

        RootManager.ModuleState modState = status != null
                ? status.moduleState
                : RootManager.ModuleState.UNSUPPORTED;
        updateModuleUi(rooted, modState);
    }

    private void updateModuleUi(boolean rooted, RootManager.ModuleState state) {
        switch (state) {
            case ACTIVE:
                moduleStatusText.setText(R.string.root_status_privileged);
                installBtn.setText(R.string.root_update_module);
                installBtn.setVisibility(rooted ? View.VISIBLE : View.GONE);
                uninstallBtn.setVisibility(rooted ? View.VISIBLE : View.GONE);
                rebootBtn.setVisibility(View.GONE);
                break;
            case PENDING_REBOOT_INSTALL:
                moduleStatusText.setText(R.string.root_status_pending_reboot);
                installBtn.setText(R.string.root_update_module);
                installBtn.setVisibility(rooted ? View.VISIBLE : View.GONE);
                uninstallBtn.setVisibility(rooted ? View.VISIBLE : View.GONE);
                rebootBtn.setVisibility(rooted ? View.VISIBLE : View.GONE);
                break;
            case PENDING_REBOOT_REMOVE:
                moduleStatusText.setText(R.string.root_status_pending_remove);
                installBtn.setText(R.string.root_install_module);
                installBtn.setVisibility(rooted ? View.VISIBLE : View.GONE);
                uninstallBtn.setVisibility(View.GONE);
                rebootBtn.setVisibility(rooted ? View.VISIBLE : View.GONE);
                break;
            case PRIVILEGED_EXTERNAL:
                moduleStatusText.setText(R.string.root_status_privileged);
                installBtn.setVisibility(View.GONE);
                uninstallBtn.setVisibility(View.GONE);
                rebootBtn.setVisibility(View.GONE);
                break;
            case NOT_INSTALLED:
                moduleStatusText.setText(R.string.root_status_available);
                installBtn.setText(R.string.root_install_module);
                installBtn.setVisibility(rooted ? View.VISIBLE : View.GONE);
                uninstallBtn.setVisibility(View.GONE);
                rebootBtn.setVisibility(View.GONE);
                break;
            case UNSUPPORTED:
            default:
                moduleStatusText.setText(rooted
                        ? R.string.root_status_available
                        : R.string.root_status_not_rooted);
                installBtn.setText(R.string.root_install_module);
                installBtn.setVisibility(View.VISIBLE);
                installBtn.setEnabled(rooted && !busy);
                uninstallBtn.setVisibility(View.GONE);
                rebootBtn.setVisibility(View.GONE);
                return;
        }
        installBtn.setEnabled(rooted && !busy);
        uninstallBtn.setEnabled(rooted && !busy);
        rebootBtn.setEnabled(rooted && !busy);
    }

    private void updateChildControlState(View container,
                                         MaterialSwitch switchView,
                                         boolean enabled,
                                         boolean checked) {
        container.setAlpha(enabled ? 1f : 0.6f);
        if (enabled) {
            container.setOnClickListener(v -> switchView.performClick());
        } else {
            container.setOnClickListener(null);
        }
        switchView.setChecked(enabled && checked);
        switchView.jumpDrawablesToCurrentState();
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        if (refreshBtn != null) {
            refreshBtn.setEnabled(!busy);
        }
        if (installBtn != null && lastStatus != null) {
            installBtn.setEnabled(lastStatus.rooted && !busy);
        }
        if (uninstallBtn != null && lastStatus != null) {
            uninstallBtn.setEnabled(lastStatus.rooted && !busy);
        }
        if (rebootBtn != null && lastStatus != null) {
            rebootBtn.setEnabled(lastStatus.rooted && !busy);
        }
    }

    private void showFeedback(String msg) {
        if (feedbackText != null && msg != null && !msg.isEmpty()) {
            feedbackText.setText(msg);
            feedbackText.setVisibility(View.VISIBLE);
        }
    }

    private void hideFeedback() {
        if (feedbackText != null) {
            feedbackText.setVisibility(View.GONE);
        }
    }

    private String formatProvider(RootManager.RootProvider provider) {
        switch (provider) {
            case MAGISK:
                return "Magisk";
            case KERNELSU:
                return "KernelSU";
            case APATCH:
                return "APatch";
            case GENERIC_SU:
                return "Root (su)";
            case NONE:
            default:
                return resources.getString(R.string.unavailable);
        }
    }

    private void setupSwitch(MaterialSwitch switchView, @Nullable View container,
                             boolean defaultValue, CompoundButton.OnCheckedChangeListener listener) {
        switchView.setChecked(defaultValue);
        switchView.jumpDrawablesToCurrentState();
        switchView.setOnCheckedChangeListener(listener);

        if (container != null) {
            container.setOnClickListener(view -> switchView.performClick());
        }
    }

    @Override
    protected boolean blurBehind() {
        return true;
    }

    @Override
    protected int getDesiredInitialState() {
        return BottomSheetBehavior.STATE_EXPANDED;
    }
}
