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

package com.stario.launcher.ui.keyboard.extract;

import android.app.Dialog;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;

import androidx.activity.ComponentDialog;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.button.MaterialButton;
import com.stario.launcher.R;
import com.stario.launcher.themes.ThemedActivity;
import com.stario.launcher.ui.Measurements;
import com.stario.launcher.ui.keyboard.InlineAutocompleteEditText;
import com.stario.launcher.ui.keyboard.KeyboardHeightProvider;
import com.stario.launcher.ui.utils.UiUtils;
import com.stario.launcher.utils.objects.ObservableObject;

public class ExtractDialog extends DialogFragment {
    private final KeyboardHeightProvider heightProvider;
    private final ExtractEditText editText;
    private final ThemedActivity activity;

    private InlineAutocompleteEditText extractedEditText;
    private ObservableObject.OnSet<Integer> statusBarListener;
    private ObservableObject.OnSet<Integer> navListener;
    private KeyboardHeightProvider.KeyboardHeightListener keyboardHeightListener;
    private TextWatcher textWatcher;
    private Runnable showKeyboardRunnable;
    private boolean shown;

    public ExtractDialog(@NonNull ExtractEditText editText) {
        this.editText = editText;
        this.activity = (ThemedActivity) editText.getContext();
        this.heightProvider = new KeyboardHeightProvider(activity);
        this.shown = false;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        int theme = getTheme();

        if (theme == 0) {
            TypedValue outValue = new TypedValue();

            if (activity.getTheme()
                    .resolveAttribute(com.google.android.material.R.attr.bottomSheetDialogTheme, outValue, true)) {
                theme = outValue.resourceId;
            } else {
                theme = com.google.android.material.R.style.Theme_Design_Light_BottomSheetDialog;
            }
        }

        Dialog dialog = new ComponentDialog(activity, theme);

        dialog.setOnShowListener(dialogInterface -> {
            if (extractedEditText != null) {
                extractedEditText.setMaxLines(editText.getMaxLines());
                extractedEditText.setImeOptions(editText.getImeOptions() | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
                extractedEditText.setSingleLine(editText.isSingleLine());
                extractedEditText.setHint(editText.getHint());

                Editable text = editText.getText();

                if(text != null) {
                    extractedEditText.setText(text);
                    extractedEditText.setSelection(text.length());
                }
            }
        });

        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();

        Dialog dialog = getDialog();

        if (dialog != null) {
            Window window = dialog.getWindow();

            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);

                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

                window.setWindowAnimations(R.style.ExtractedEditTextDialogAnimations);

                UiUtils.makeSysUITransparent(window);
                window.getDecorView().setBackgroundColor(
                        activity.getAttributeData(com.google.android.material.R.attr.colorSurface)
                );
            }
        }

        shown = false;

        heightProvider.start();
    }

    @Override
    public void onStop() {
        super.onStop();

        if (showKeyboardRunnable != null) {
            UiUtils.removeUICallback(showKeyboardRunnable);
        }
        heightProvider.close();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.extract_dialog, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        UiUtils.Notch.applyNotchMargin(view);

        MaterialButton done = view.findViewById(R.id.proceed);
        done.setOnClickListener(v -> dismiss());

        extractedEditText = view.findViewById(R.id.edit_text);
        extractedEditText.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS |
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        extractedEditText.setAutocompleteProvider(editText.getAutocompleteProvider());
        textWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (shown) {
                    editText.setText(s);
                }
            }
        };
        extractedEditText.addTextChangedListener(textWatcher);

        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();

        statusBarListener = value -> {
            params.topMargin = value;

            view.requestLayout();
        };
        Measurements.addStatusBarListener(statusBarListener);

        navListener = value -> {
            params.bottomMargin = value + heightProvider.getKeyboardHeight();

            view.requestLayout();
        };
        Measurements.addNavListener(navListener);

        keyboardHeightListener = height -> {
            if (height <= 0) {
                if (shown) {
                    ExtractDialog.this.dismiss();

                    shown = false;
                }
            } else {
                shown = true;
            }

            params.bottomMargin = height + Measurements.getNavHeight();

            view.requestLayout();
        };
        heightProvider.addKeyboardHeightListener(keyboardHeightListener);

        showKeyboardRunnable = new Runnable() {
            @Override
            public void run() {
                if (!shown && isAdded() && extractedEditText != null) {
                    UiUtils.showKeyboard(extractedEditText);
                    UiUtils.postDelayed(this, 50);
                }
            }
        };
        UiUtils.post(showKeyboardRunnable);
    }

    @Override
    public void onDestroyView() {
        if (showKeyboardRunnable != null) {
            UiUtils.removeUICallback(showKeyboardRunnable);
            showKeyboardRunnable = null;
        }

        if (statusBarListener != null) {
            Measurements.removeStatusBarListener(statusBarListener);
            statusBarListener = null;
        }

        if (navListener != null) {
            Measurements.removeNavListener(navListener);
            navListener = null;
        }

        if (keyboardHeightListener != null) {
            heightProvider.removeKeyboardHeightListener(keyboardHeightListener);
            keyboardHeightListener = null;
        }

        if (extractedEditText != null && textWatcher != null) {
            extractedEditText.removeTextChangedListener(textWatcher);
            textWatcher = null;
        }

        extractedEditText = null;

        super.onDestroyView();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        dismiss();
    }

    @Override
    public void dismiss() {
        if (extractedEditText != null) {
            editText.setText(extractedEditText.getText());
        }

        super.dismiss();
    }
}
