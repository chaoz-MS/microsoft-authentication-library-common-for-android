// Copyright (c) Microsoft Corporation.
// All rights reserved.
//
// This code is licensed under the MIT License.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files(the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and / or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions :
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.
package com.microsoft.identity.common.components;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.microsoft.identity.common.internal.providers.oauth2.AndroidTaskStateGenerator;
import com.microsoft.identity.common.internal.ui.AndroidAuthorizationStrategyFactory;
import com.microsoft.identity.common.internal.ui.browser.AndroidBrowserSelector;
import com.microsoft.identity.common.java.interfaces.IPlatformComponents;
import com.microsoft.identity.common.java.interfaces.PlatformComponents;

import lombok.NonNull;

public class AndroidPlatformComponentsFactoryExt {
    /**
     * Creates an {@link IPlatformComponents} object from an {@link Activity} and, optionally, a {@link Fragment}.
     *
     * @param activity an activity where an interactive session will be attached to.
     * @param fragment a fragment where an interactive session will be attached to.
     **/
    public static IPlatformComponents createFromActivity(@NonNull final Activity activity,
                                                         @Nullable final Fragment fragment) {
        Context context = activity.getApplicationContext();

        AndroidPlatformComponentsFactory.initializeGlobalStates(context);

        final PlatformComponents.PlatformComponentsBuilder builder = PlatformComponents.builder();
        AndroidPlatformComponentsFactory.fillBuilderWithBasicImplementations(builder, context, activity, fragment);
        builder.authorizationStrategyFactory(
                        AndroidAuthorizationStrategyFactory.builder()
                                .context(context)
                                .activity(activity)
                                .fragment(fragment)
                                .browserSelector(new AndroidBrowserSelector(context))
                                .build())
                .stateGenerator(new AndroidTaskStateGenerator(activity.getTaskId()));
        return builder.build();
    }
}
