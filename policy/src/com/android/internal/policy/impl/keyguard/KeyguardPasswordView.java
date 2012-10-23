/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.policy.impl.keyguard;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import com.android.internal.R;
import com.android.internal.widget.LockPatternUtils;
import java.util.List;

import android.app.admin.DevicePolicyManager;
import android.content.res.Configuration;
import android.graphics.Rect;

import com.android.internal.widget.PasswordEntryKeyboardView;

import android.os.CountDownTimer;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.text.method.TextKeyListener;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;

import com.android.internal.widget.PasswordEntryKeyboardHelper;
/**
 * Displays a dialer-like interface or alphanumeric (latin-1) key entry for the user to enter
 * an unlock password
 */

public class KeyguardPasswordView extends KeyguardAbsKeyInputView
        implements KeyguardSecurityView, OnEditorActionListener, TextWatcher {

    private PasswordEntryKeyboardView mKeyboardView;
    private PasswordEntryKeyboardHelper mKeyboardHelper;
    private boolean mIsAlpha;

    public KeyguardPasswordView(Context context) {
        super(context);
    }

    public KeyguardPasswordView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    protected void resetState() {
        mSecurityMessageDisplay.setMessage(
                mIsAlpha ? R.string.kg_password_instructions : R.string.kg_pin_instructions, false);
        mPasswordEntry.setEnabled(true);
        mKeyboardView.setEnabled(true);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        final int quality = mLockPatternUtils.getKeyguardStoredPasswordQuality();
        mIsAlpha = DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC == quality
                || DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC == quality
                || DevicePolicyManager.PASSWORD_QUALITY_COMPLEX == quality;

        mKeyboardView = (PasswordEntryKeyboardView) findViewById(R.id.keyboard);

        mKeyboardHelper = new PasswordEntryKeyboardHelper(mContext, mKeyboardView, this, false,
                new int[] {
                    R.xml.kg_password_kbd_numeric,
                    com.android.internal.R.xml.password_kbd_qwerty,
                    com.android.internal.R.xml.password_kbd_qwerty_shifted,
                    com.android.internal.R.xml.password_kbd_symbols,
                    com.android.internal.R.xml.password_kbd_symbols_shift
                    }
        );
        mKeyboardHelper.setEnableHaptics(mLockPatternUtils.isTactileFeedbackEnabled());

        boolean imeOrDeleteButtonVisible = false;
        if (mIsAlpha) {
            // We always use the system IME for alpha keyboard, so hide lockscreen's soft keyboard
            mKeyboardHelper.setKeyboardMode(PasswordEntryKeyboardHelper.KEYBOARD_MODE_ALPHA);
            mKeyboardView.setVisibility(View.GONE);
        } else {
            mKeyboardHelper.setKeyboardMode(PasswordEntryKeyboardHelper.KEYBOARD_MODE_NUMERIC);

            // Use lockscreen's numeric keyboard if the physical keyboard isn't showing
            boolean hardKeyboardVisible = getResources().getConfiguration().hardKeyboardHidden
                    == Configuration.HARDKEYBOARDHIDDEN_NO;
            mKeyboardView.setVisibility(
                    (ENABLE_HIDE_KEYBOARD && hardKeyboardVisible) ? View.INVISIBLE : View.VISIBLE);

            // The delete button is of the PIN keyboard itself in some (e.g. tablet) layouts,
            // not a separate view
            View pinDelete = findViewById(R.id.delete_button);
            if (pinDelete != null) {
                pinDelete.setVisibility(View.VISIBLE);
                imeOrDeleteButtonVisible = true;
                pinDelete.setOnClickListener(new OnClickListener() {
                    public void onClick(View v) {
                        mKeyboardHelper.handleBackspace();
                    }
                });
            }
        }

        // This allows keyboards with overlapping qwerty/numeric keys to choose just numeric keys.
        if (mIsAlpha) {
            mPasswordEntry.setKeyListener(TextKeyListener.getInstance());
            mPasswordEntry.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            mPasswordEntry.setKeyListener(DigitsKeyListener.getInstance());
            mPasswordEntry.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        }

        // Poke the wakelock any time the text is selected or modified
        mPasswordEntry.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                mCallback.userActivity(0); // TODO: customize timeout for text?
            }
        });

        mPasswordEntry.addTextChangedListener(new TextWatcher() {
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void afterTextChanged(Editable s) {
                if (mCallback != null) {
                    mCallback.userActivity(0);
                }
            }
        });

        mPasswordEntry.requestFocus();

        // If there's more than one IME, enable the IME switcher button
        View switchImeButton = findViewById(R.id.switch_ime_button);
        final InputMethodManager imm = (InputMethodManager) getContext().getSystemService(
                Context.INPUT_METHOD_SERVICE);
        if (mIsAlpha && switchImeButton != null && hasMultipleEnabledIMEsOrSubtypes(imm, false)) {
            switchImeButton.setVisibility(View.VISIBLE);
            imeOrDeleteButtonVisible = true;
            switchImeButton.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    mCallback.userActivity(0); // Leave the screen on a bit longer
                    imm.showInputMethodPicker();
                }
            });
        }

        // If no icon is visible, reset the start margin on the password field so the text is
        // still centered.
        if (!imeOrDeleteButtonVisible) {
            android.view.ViewGroup.LayoutParams params = mPasswordEntry.getLayoutParams();
            if (params instanceof MarginLayoutParams) {
                final MarginLayoutParams mlp = (MarginLayoutParams) params;
                mlp.setMarginStart(0);
                mPasswordEntry.setLayoutParams(params);
            }
        }
    }

    /**
     * Method adapted from com.android.inputmethod.latin.Utils
     *
     * @param imm The input method manager
     * @param shouldIncludeAuxiliarySubtypes
     * @return true if we have multiple IMEs to choose from
     */
    private boolean hasMultipleEnabledIMEsOrSubtypes(InputMethodManager imm,
            final boolean shouldIncludeAuxiliarySubtypes) {
        final List<InputMethodInfo> enabledImis = imm.getEnabledInputMethodList();

        // Number of the filtered IMEs
        int filteredImisCount = 0;

        for (InputMethodInfo imi : enabledImis) {
            // We can return true immediately after we find two or more filtered IMEs.
            if (filteredImisCount > 1) return true;
            final List<InputMethodSubtype> subtypes =
                    imm.getEnabledInputMethodSubtypeList(imi, true);
            // IMEs that have no subtypes should be counted.
            if (subtypes.isEmpty()) {
                ++filteredImisCount;
                continue;
            }

            int auxCount = 0;
            for (InputMethodSubtype subtype : subtypes) {
                if (subtype.isAuxiliary()) {
                    ++auxCount;
                }
            }
            final int nonAuxCount = subtypes.size() - auxCount;

            // IMEs that have one or more non-auxiliary subtypes should be counted.
            // If shouldIncludeAuxiliarySubtypes is true, IMEs that have two or more auxiliary
            // subtypes should be counted as well.
            if (nonAuxCount > 0 || (shouldIncludeAuxiliarySubtypes && auxCount > 1)) {
                ++filteredImisCount;
                continue;
            }
        }

        return filteredImisCount > 1
        // imm.getEnabledInputMethodSubtypeList(null, false) will return the current IME's enabled
        // input method subtype (The current IME should be LatinIME.)
                || imm.getEnabledInputMethodSubtypeList(null, false).size() > 1;
    }
}

