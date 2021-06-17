/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.settingslib.enterprise;

import android.content.Context;
import android.content.DialogInterface;
import android.util.Log;

import androidx.annotation.Nullable;

public class BiometricActionDisabledByAdminController extends BaseActionDisabledByAdminController {

    private static final String TAG = "BiometricActionDisabledByAdminController";

    BiometricActionDisabledByAdminController(
            DeviceAdminStringProvider stringProvider) {
        super(stringProvider);
    }

    @Override
    public void setupLearnMoreButton(Context context) {

    }

    @Override
    public String getAdminSupportTitle(@Nullable String restriction) {
        return mStringProvider.getDisabledBiometricsParentConsentTitle();
    }

    @Override
    public CharSequence getAdminSupportContentString(Context context,
            @Nullable CharSequence supportMessage) {
        return mStringProvider.getDisabledBiometricsParentConsentContent();
    }

    @Override
    public DialogInterface.OnClickListener getPositiveButtonListener() {
        return (dialog, which) -> {
            Log.d(TAG, "Positive button clicked");
            // TODO(b/188847063) Launch appropriate intent
        };
    }
}
